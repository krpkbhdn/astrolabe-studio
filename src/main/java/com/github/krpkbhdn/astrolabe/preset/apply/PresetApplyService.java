package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.preset.PresetApplicationService;
import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;
import com.github.krpkbhdn.astrolabe.preset.ProjectPreset;
import com.github.krpkbhdn.astrolabe.preset.ProjectPresetService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies a preset to a project folder without a Claude session. The CLI writes enabledPlugins into settings.json while installing
 * plugins, so settings are merged last; one failing component never stops the others.
 */
@Service
public class PresetApplyService {

    private static final Logger log = LoggerFactory.getLogger(PresetApplyService.class);
    private static final Comparator<PresetComponentKind> APPLY_ORDER = Comparator.comparingInt(kind -> switch (kind) {
        case MARKETPLACE -> 0;
        case PLUGIN -> 1;
        case MCP_SERVER -> 2;
        case SKILL, AGENT, FILE, CODE_MODE -> 3;
        case INSTRUCTION -> 4;
        case SETTINGS -> 5;
    });

    private final ProjectPresetService presets;
    private final PresetApplicationService applications;
    private final Map<PresetComponentKind, PresetComponentApplier> appliers = new EnumMap<>(PresetComponentKind.class);
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    PresetApplyService(ProjectPresetService presets, PresetApplicationService applications, List<PresetComponentApplier> appliers) {
        this.presets = presets;
        this.applications = applications;
        appliers.forEach(applier -> this.appliers.put(applier.kind(), applier));
    }

    /** @return one line per component in preset order; nothing is written */
    public List<PlannedChange> plan(long presetId, Path projectDir) {
        return plan(List.of(presetId), projectDir);
    }

    /** Layers in the given order; a component (same kind and name) repeated in a later layer is listed once, from the first. */
    public List<PlannedChange> plan(List<Long> presetIds, Path projectDir) {
        return locked(projectDir, () -> {
            var layers = loadAll(presetIds);
            var differing = differingLayers(layers);
            return mergedComponents(layers).stream().map(component -> {
                var change = plan(component, projectDir);
                var others = differing.getOrDefault(keyOf(component), List.of());
                return others.isEmpty() ? change : change.withDetail(change.detail() + "; also in " + String.join(", ", others) + ", differs");
            }).toList();
        });
    }

    private List<ProjectPreset> loadAll(List<Long> presetIds) {
        return presetIds.stream().map(id -> presets.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown preset " + id))).toList();
    }

    private static List<Object> keyOf(PresetComponent component) {
        return List.of(component.kind(), component.name());
    }

    private static List<PresetComponent> mergedComponents(List<ProjectPreset> layers) {
        var unique = new LinkedHashMap<List<Object>, PresetComponent>();
        layers.forEach(layer -> layer.components().forEach(component -> unique.putIfAbsent(keyOf(component), component)));
        return List.copyOf(unique.values());
    }

    /** Per component key, the names of the later layers whose copy has another scope or payload than the first one, which wins. */
    private static Map<List<Object>, List<String>> differingLayers(List<ProjectPreset> layers) {
        var first = new LinkedHashMap<List<Object>, PresetComponent>();
        var differing = new LinkedHashMap<List<Object>, List<String>>();
        for (var layer : layers) {
            for (var component : layer.components()) {
                var winner = first.putIfAbsent(keyOf(component), component);
                if (winner != null && !winner.payload().equals(component.payload())) {
                    differing.computeIfAbsent(keyOf(component), key -> new ArrayList<>()).add(layer.name());
                }
            }
        }
        return differing;
    }

    private <T> T locked(Path projectDir, Supplier<T> action) {
        var lock = locks.computeIfAbsent(projectDir.toAbsolutePath().normalize(), key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Plans again, since the project may have changed after the preview, then applies the selected components that can be applied. */
    public PresetApplyResult apply(long projectId, long presetId, Path projectDir, Set<Long> selectedComponentIds) {
        return apply(projectId, List.of(presetId), projectDir, selectedComponentIds);
    }

    /** Applies the merged layers as one plan, so a component two layers share is written once; a layer is recorded when nothing failed and one of its components was applied or already there. */
    public PresetApplyResult apply(long projectId, List<Long> presetIds, Path projectDir, Set<Long> selectedComponentIds) {
        return locked(projectDir, () -> applyLocked(projectId, loadAll(presetIds), projectDir, selectedComponentIds));
    }

    private PresetApplyResult applyLocked(long projectId, List<ProjectPreset> layers, Path projectDir, Set<Long> selectedComponentIds) {
        var ordered = mergedComponents(layers).stream().sorted(Comparator.comparing(PresetComponent::kind, APPLY_ORDER)).toList();
        var outcomes = new ArrayList<ComponentOutcome>();
        var applied = 0;
        var allPresent = true;
        var settled = new HashSet<List<Object>>();
        for (var component : ordered) {
            var change = plan(component, projectDir);
            allPresent &= change.action() == ChangeAction.PRESENT;
            var outcome = !selectedComponentIds.contains(component.id()) ? ComponentOutcome.of(component, OutcomeStatus.SKIPPED, "Not selected")
                    : change.action().writes() ? applyOne(component, projectDir)
                    : ComponentOutcome.of(component, OutcomeStatus.SKIPPED, change.detail());
            var done = outcome.status() == OutcomeStatus.APPLIED || outcome.status() == OutcomeStatus.SIGN_IN_NEEDED;
            applied += done ? 1 : 0;
            if (done || change.action() == ChangeAction.PRESENT) {
                settled.add(keyOf(component));
            }
            outcomes.add(outcome);
        }
        for (var applier : appliers.values()) {
            outcomes = new ArrayList<>(applier.afterApply(outcomes, projectDir));
        }
        var failed = outcomes.stream().anyMatch(outcome -> outcome.status() == OutcomeStatus.FAILED);
        var recorded = !failed && (applied > 0 || allPresent);
        if (recorded) {
            layers.stream().filter(layer -> layer.components().stream().anyMatch(component -> settled.contains(keyOf(component))))
                    .forEach(layer -> applications.markApplied(projectId, layer.id(), layer.revision()));
        }
        return new PresetApplyResult(outcomes, recorded);
    }

    private PlannedChange plan(PresetComponent component, Path projectDir) {
        try {
            return appliers.get(component.kind()).plan(component, projectDir);
        } catch (Exception e) {
            log.warn("Could not plan {} component {}: {}", component.kind(), component.id(), e.getClass().getSimpleName());
            return PlannedChange.of(component, ChangeAction.CONFLICT, "Could not check: " + ErrorText.redacted(e));
        }
    }

    private ComponentOutcome applyOne(PresetComponent component, Path projectDir) {
        try {
            return appliers.get(component.kind()).apply(component, projectDir);
        } catch (Exception e) {
            log.warn("Could not apply {} component {}: {}", component.kind(), component.id(), e.getClass().getSimpleName());
            return ComponentOutcome.of(component, OutcomeStatus.FAILED, ErrorText.redacted(e));
        }
    }
}
