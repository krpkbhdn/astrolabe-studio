package com.github.krpkbhdn.astrolabe.preset;

import com.github.krpkbhdn.astrolabe.config.NameRules;
import com.github.krpkbhdn.astrolabe.workspace.ChangeListeners;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Project presets in SQLite: bundled ones are seeded and kept in step with the app, user ones are plain rows.
 * Every change of the components raises the preset's revision, which tells projects that applied it that it moved on.
 */
@Service
public class ProjectPresetService {

    public static final int MAX_NAME = 60;
    public static final int MAX_DESCRIPTION = 200;

    private static final Logger log = LoggerFactory.getLogger(ProjectPresetService.class);

    private final ProjectPresetRepository repository;
    private final TransactionTemplate transaction;
    private final BundledPresetLoader bundled;
    private final ChangeListeners listeners = new ChangeListeners("Project preset");

    public ProjectPresetService(JdbcClient jdbc, TransactionTemplate transaction, BundledPresetLoader bundled) {
        this.repository = new ProjectPresetRepository(jdbc, bundled);
        this.transaction = transaction;
        this.bundled = bundled;
    }

    /** Bundled presets in the order of presets.json (General first), then the others by name; never by database id. */
    public List<ProjectPreset> list() {
        var shippedOrder = bundled.presets().stream().map(BundledPreset::key).toList();
        return repository.list().stream().sorted(listOrder(shippedOrder)).toList();
    }

    static Comparator<ProjectPreset> listOrder(List<String> shippedOrder) {
        return Comparator.comparingInt((ProjectPreset preset) -> preset.bundledKey() == null || !shippedOrder.contains(preset.bundledKey()) ? shippedOrder.size() : shippedOrder.indexOf(preset.bundledKey()))
                .thenComparing(ProjectPreset::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(ProjectPreset::id);
    }

    public Optional<ProjectPreset> get(long id) {
        return repository.find(id);
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid input */
    public ProjectPreset create(String name, String description) {
        return insertFree(validate(name, description), description.strip(), List.of());
    }

    private ProjectPreset insertFree(String cleanName, String description, List<PresetComponentSpec> components) {
        var id = transaction.execute(status -> {
            requireFreeName(cleanName, -1);
            return repository.insert(cleanName, description, null, null, components);
        });
        return firedFor(id);
    }

    private ProjectPreset firedFor(long id) {
        listeners.fire();
        return require(id);
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid input */
    public ProjectPreset update(long id, String name, String description) {
        require(id);
        var cleanName = validate(name, description);
        transaction.executeWithoutResult(status -> {
            requireFreeName(cleanName, id);
            repository.rename(id, cleanName, description.strip());
        });
        return firedFor(id);
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid input */
    public ProjectPreset duplicate(long id, String newName) {
        var source = require(id);
        var cleanName = validate(newName, source.description());
        return insertFree(cleanName, source.description(), source.components().stream().map(c -> new PresetComponentSpec(c.kind(), c.name(), c.payload())).toList());
    }

    /** A bundled preset is only marked deleted, so it is not seeded again; a user preset is removed. */
    public void delete(long id) {
        var changed = transaction.execute(status -> repository.markBundledDeleted(id) + repository.deleteUser(id));
        if (changed != null && changed > 0) {
            listeners.fire();
        }
    }

    /** Resets one bundled preset to its shipped content, also bringing back a deleted one. */
    public void restore(String key) {
        var preset = bundled.presets().stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No bundled preset: " + key));
        transaction.executeWithoutResult(status -> restore(preset));
        listeners.fire();
    }

    public void restoreAll() {
        for (var preset : bundled.presets()) {
            try {
                transaction.executeWithoutResult(status -> restore(preset));
            } catch (IllegalArgumentException e) {
                log.warn("Bundled preset {} not restored: its name is used by another preset", preset.key());
            }
        }
        listeners.fire();
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid input */
    public PresetComponent addComponent(long presetId, PresetComponentKind kind, String name, JsonNode payload) {
        require(presetId);
        var spec = PresetComponentValidator.validate(kind, name, payload);
        var id = transaction.execute(status -> {
            requireFreeComponent(presetId, spec, -1);
            var created = repository.insertComponent(presetId, spec);
            repository.touch(presetId);
            return created;
        });
        listeners.fire();
        return component(id);
    }

    /** Changes name and payload of a component; its kind stays. */
    public PresetComponent updateComponent(long componentId, String name, JsonNode payload) {
        var current = component(componentId);
        var presetId = presetOf(componentId);
        var spec = PresetComponentValidator.validate(current.kind(), name, payload);
        transaction.executeWithoutResult(status -> {
            requireFreeComponent(presetId, spec, componentId);
            repository.updateComponent(componentId, spec);
            repository.touch(presetId);
        });
        listeners.fire();
        return component(componentId);
    }

    public void removeComponent(long componentId) {
        var presetId = presetOf(componentId);
        transaction.executeWithoutResult(status -> {
            repository.deleteComponent(componentId);
            repository.touch(presetId);
        });
        listeners.fire();
    }

    /** Swaps the component with its nearest neighbour of the same kind (the UI lists one kind at a time); at the end of the list nothing happens. */
    public void moveComponent(long componentId, boolean up) {
        var presetId = presetOf(componentId);
        var moved = Boolean.TRUE.equals(transaction.execute(status -> swapWithNeighbour(presetId, componentId, up)));
        if (moved) {
            listeners.fire();
        }
    }

    /** Seeds missing bundled presets (not deleted ones) and upgrades unedited ones whose shipped version rose. */
    @PostConstruct
    public void syncBundled() {
        for (var preset : bundled.presets()) {
            try {
                transaction.executeWithoutResult(status -> sync(preset));
            } catch (RuntimeException e) {
                log.warn("Bundled preset {} skipped: {}", preset.key(), e.getClass().getSimpleName());
            }
        }
        listeners.fire();
    }

    /** @return unsubscribe handle; callback fires (any thread) after any change made through this service */
    public Runnable subscribe(Runnable onChange) {
        return listeners.add(onChange);
    }

    private boolean swapWithNeighbour(long presetId, long componentId, boolean up) {
        var all = repository.siblings(presetId, componentId);
        var index = 0;
        while (index < all.size() && all.get(index)[0] != componentId) {
            index++;
        }
        var other = up ? index - 1 : index + 1;
        if (index >= all.size() || other < 0 || other >= all.size()) {
            return false;
        }
        repository.setPosition(componentId, all.get(other)[1]);
        repository.setPosition(all.get(other)[0], all.get(index)[1]);
        repository.touch(presetId);
        return true;
    }

    private void sync(BundledPreset preset) {
        var row = repository.bundledState(preset.key());
        if (row.isEmpty()) {
            requireFreeName(preset.name(), -1);
            repository.insert(preset.name(), preset.description(), preset.key(), preset.version(), preset.components());
        } else if (!row.get().deleted() && !row.get().edited() && row.get().version() < preset.version()) {
            restore(preset);
        }
    }

    private void restore(BundledPreset preset) {
        var existing = repository.bundledId(preset.key());
        var ownId = existing.orElse(-1L);
        if (repository.nameTaken(preset.name(), ownId)) {
            throw new IllegalArgumentException("A preset named \"" + preset.name() + "\" already exists: rename it first.");
        }
        if (existing.isEmpty()) {
            repository.insert(preset.name(), preset.description(), preset.key(), preset.version(), preset.components());
            return;
        }
        repository.resetBundled(existing.get(), preset);
    }

    private void requireFreeComponent(long presetId, PresetComponentSpec spec, long ownId) {
        if (repository.componentNameTaken(presetId, spec, ownId)) {
            throw new IllegalArgumentException("The preset already has a " + spec.kind() + " named \"" + spec.name() + "\".");
        }
        var secondSettings = spec.kind() == PresetComponentKind.SETTINGS && repository.hasOtherSettings(presetId, ownId);
        if (secondSettings) {
            throw new IllegalArgumentException("A preset can have only one settings component.");
        }
    }

    private ProjectPreset require(long id) {
        return get(id).orElseThrow(() -> new IllegalArgumentException("Preset not found: " + id));
    }

    public Optional<PresetComponent> findComponent(long componentId) {
        return repository.findComponent(componentId);
    }

    private PresetComponent component(long componentId) {
        return findComponent(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));
    }

    private long presetOf(long componentId) {
        return repository.presetOf(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));
    }

    private void requireFreeName(String name, long ownId) {
        if (repository.nameTaken(name, ownId)) {
            throw new IllegalArgumentException("A preset named \"" + name + "\" already exists.");
        }
    }

    private String validate(String name, String description) {
        var cleanName = NameRules.requireName(name, MAX_NAME);
        if (description == null) {
            throw new IllegalArgumentException("The description must not be missing.");
        }
        if (description.strip().length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("The description is longer than " + MAX_DESCRIPTION + " characters.");
        }
        return cleanName;
    }
}
