package com.github.krpkbhdn.ccmanager.preset.apply;

import com.github.krpkbhdn.ccmanager.git.GitLocalExclude;
import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Writes an instruction as a managed block in the project's CLAUDE.md, or in CLAUDE.local.md (kept out of git) for the local scope. */
@Component
class InstructionApplier implements PresetComponentApplier {

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String EDITED_SHARED_DETAIL = "managed block in CLAUDE.md was edited, move it by hand";

    private final GitLocalExclude localExclude;

    InstructionApplier(GitLocalExclude localExclude) {
        this.localExclude = localExclude;
    }

    @Override
    public PresetComponentKind kind() {
        return PresetComponentKind.INSTRUCTION;
    }

    @Override
    public PlannedChange plan(PresetComponent component, Path projectDir) throws IOException {
        var target = targetOf(component);
        var plan = ClaudeMdSections.plan(target, ProjectFiles.read(ProjectFiles.resolve(projectDir, target)), component.name(), markdown(component));
        var shared = sharedBlock(component, projectDir);
        if (shared == SharedBlock.EDITED) {
            return PlannedChange.of(component, ChangeAction.CONFLICT, EDITED_SHARED_DETAIL);
        }
        var moves = shared == SharedBlock.IDENTICAL;
        var preview = ClaudeMdSections.block(component.name(), markdown(component));
        if (moves && plan.action() == ChangeAction.PRESENT) {
            return PlannedChange.of(component, ChangeAction.UPDATE, "Move from " + CLAUDE_MD + " to " + LocalConfigFiles.CLAUDE_MD).withPreview(preview);
        }
        var detail = moves && plan.text() != null ? plan.detail() + ", move from " + CLAUDE_MD + " to " + LocalConfigFiles.CLAUDE_MD : plan.detail();
        var change = PlannedChange.of(component, plan.action(), detail);
        return plan.text() == null ? change : change.withPreview(preview);
    }

    @Override
    public ComponentOutcome apply(PresetComponent component, Path projectDir) throws IOException {
        var target = targetOf(component);
        var file = ProjectFiles.resolve(projectDir, target);
        var seen = ProjectFiles.read(file);
        var plan = ClaudeMdSections.plan(target, seen, component.name(), markdown(component));
        if (plan.action() == ChangeAction.CONFLICT) {
            return ComponentOutcome.of(component, OutcomeStatus.SKIPPED, plan.detail());
        }
        var shared = sharedBlock(component, projectDir);
        if (shared == SharedBlock.EDITED) {
            return ComponentOutcome.of(component, OutcomeStatus.SKIPPED, EDITED_SHARED_DETAIL);
        }
        if (ProjectFiles.isLocal(component)) {
            localExclude.ensureExcluded(projectDir, LocalConfigFiles.CLAUDE_MD);
        }
        if (plan.text() != null) {
            ProjectFiles.replaceIfUnchanged(file, seen, plan.text());
        }
        if (shared == SharedBlock.IDENTICAL) {
            try {
                removeFromShared(component, projectDir);
            } catch (IOException | IllegalArgumentException e) {
                return ComponentOutcome.of(component, OutcomeStatus.APPLIED, plan.detail() + ", but the old block is still in " + CLAUDE_MD + ", remove it by hand");
            }
        }
        return ComponentOutcome.of(component, OutcomeStatus.APPLIED, plan.detail());
    }

    /** What the shared CLAUDE.md holds for a local instruction: nothing, the block this preset would write, or an edited one. */
    private static SharedBlock sharedBlock(PresetComponent component, Path projectDir) throws IOException {
        if (!ProjectFiles.isLocal(component)) {
            return SharedBlock.NONE;
        }
        var shared = ProjectFiles.read(ProjectFiles.resolve(projectDir, CLAUDE_MD));
        if (ClaudeMdSections.remove(shared, component.name()).equals(shared)) {
            return SharedBlock.NONE;
        }
        var same = ClaudeMdSections.plan(CLAUDE_MD, shared, component.name(), markdown(component)).action() == ChangeAction.PRESENT;
        return same ? SharedBlock.IDENTICAL : SharedBlock.EDITED;
    }

    private enum SharedBlock { NONE, IDENTICAL, EDITED }

    private static void removeFromShared(PresetComponent component, Path projectDir) throws IOException {
        var file = ProjectFiles.resolve(projectDir, CLAUDE_MD);
        var shared = ProjectFiles.read(file);
        var without = ClaudeMdSections.remove(shared, component.name());
        if (!without.equals(shared)) {
            ProjectFiles.replaceIfUnchanged(file, shared, without);
        }
    }

    private static String targetOf(PresetComponent component) {
        return ProjectFiles.isLocal(component) ? LocalConfigFiles.CLAUDE_MD : CLAUDE_MD;
    }

    private static String markdown(PresetComponent component) {
        return component.payload().path("markdown").asString("");
    }
}
