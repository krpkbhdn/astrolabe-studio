package com.github.krpkbhdn.ccmanager.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.krpkbhdn.ccmanager.preset.PresetComponent;
import com.github.krpkbhdn.ccmanager.preset.PresetComponentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import com.github.krpkbhdn.ccmanager.git.GitLocalExclude;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class InstructionApplierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private final GitLocalExclude exclude = mock(GitLocalExclude.class);
    private final InstructionApplier applier = new InstructionApplier(exclude);

    private static PresetComponent instruction(String title, String markdown) {
        return new PresetComponent(1, PresetComponentKind.INSTRUCTION, title, JSON.createObjectNode().put("markdown", markdown));
    }

    private String claudeMd() throws Exception {
        return Files.readString(dir.resolve("CLAUDE.md"));
    }

    @Test
    void sectionIsWrittenBetweenManagedMarkersOfItsSlug() throws Exception {
        applier.apply(instruction("Code Style", "Be terse."), dir);

        assertThat(claudeMd()).contains("<!-- cc-manager:begin code-style -->").contains("## Code Style\nBe terse.")
                .contains("<!-- cc-manager:end code-style -->");
    }

    @Test
    void existingTextIsKeptAndOnlyTheOwnBlockIsReplaced() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "# Mine\n\nhand text\n");
        applier.apply(instruction("Alpha", "one"), dir);
        applier.apply(instruction("Beta", "two"), dir);
        applier.apply(instruction("Alpha", "changed"), dir);

        assertThat(claudeMd()).startsWith("# Mine\n\nhand text\n").contains("changed").doesNotContain("one\n").contains("## Beta\ntwo");
    }

    @Test
    void textOutsideTheMarkersIsNeverTouchedOnReplace() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "before\n<!-- cc-manager:begin alpha -->\nold\n<!-- cc-manager:end alpha -->\nafter\n");

        assertThat(applier.plan(instruction("Alpha", "new"), dir).action()).isEqualTo(ChangeAction.UPDATE);
        applier.apply(instruction("Alpha", "new"), dir);

        assertThat(claudeMd()).startsWith("before\n<!-- cc-manager:begin alpha -->").endsWith("<!-- cc-manager:end alpha -->\nafter\n")
                .contains("new").doesNotContain("old");
    }

    @Test
    void sameContentIsPresentAndFileUnchanged() throws Exception {
        applier.apply(instruction("Alpha", "one"), dir);
        var before = claudeMd();

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.PRESENT);
        applier.apply(instruction("Alpha", "one"), dir);

        assertThat(claudeMd()).isEqualTo(before);
    }

    @Test
    void handWrittenSectionWithSameTitleIsAConflictAndSkippedOnApply() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "## Alpha\nmy rules\n");

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.CONFLICT);
        var outcome = applier.apply(instruction("Alpha", "one"), dir);

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.SKIPPED);
        assertThat(claudeMd()).isEqualTo("## Alpha\nmy rules\n");
    }

    @Test
    void unclosedMarkersAreAConflictAndNotRewritten() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "<!-- cc-manager:begin alpha -->\nstuff\n");

        assertThat(applier.apply(instruction("Alpha", "one"), dir).status()).isEqualTo(OutcomeStatus.SKIPPED);
        assertThat(claudeMd()).isEqualTo("<!-- cc-manager:begin alpha -->\nstuff\n");
    }

    private static PresetComponent local(String title, String markdown) {
        return new PresetComponent(1, PresetComponentKind.INSTRUCTION, title,
                JSON.createObjectNode().put("markdown", markdown).put("scope", "local"));
    }

    @Test
    void localInstructionGoesToClaudeLocalMdAndIsExcluded() throws Exception {
        assertThat(applier.plan(local("Alpha", "one"), dir).detail()).contains("CLAUDE.local.md");
        applier.apply(local("Alpha", "one"), dir);

        assertThat(Files.readString(dir.resolve("CLAUDE.local.md"))).contains("<!-- cc-manager:begin alpha -->");
        assertThat(dir.resolve("CLAUDE.md")).doesNotExist();
        verify(exclude).ensureExcluded(dir, "CLAUDE.local.md");
    }

    @Test
    void localInstructionMovesTheSharedBlockOnly() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "# Mine\n\n" + ClaudeMdSections.block("Alpha", "one") + "\n\n" + ClaudeMdSections.block("Beta", "two") + "\n");

        assertThat(applier.plan(local("Alpha", "one"), dir).detail()).containsIgnoringCase("move from CLAUDE.md to CLAUDE.local.md");
        applier.apply(local("Alpha", "one"), dir);

        assertThat(claudeMd()).isEqualTo("# Mine\n\n" + ClaudeMdSections.block("Beta", "two") + "\n");
        assertThat(Files.readString(dir.resolve("CLAUDE.local.md"))).contains("## Alpha\none");
    }

    @Test
    void editedSharedBlockIsAConflictAndNothingIsWritten() throws Exception {
        var edited = ClaudeMdSections.block("Alpha", "one edited") + "\n";
        Files.writeString(dir.resolve("CLAUDE.md"), edited);

        assertThat(applier.plan(local("Alpha", "one"), dir)).extracting(PlannedChange::action, PlannedChange::detail)
                .containsExactly(ChangeAction.CONFLICT, "managed block in CLAUDE.md was edited, move it by hand");
        assertThat(applier.apply(local("Alpha", "one"), dir).status()).isEqualTo(OutcomeStatus.SKIPPED);

        assertThat(claudeMd()).isEqualTo(edited);
        assertThat(dir.resolve("CLAUDE.local.md")).doesNotExist();
    }

    @Test
    void failedExcludeLeavesTheLocalFileUnwritten() throws Exception {
        org.mockito.Mockito.doThrow(new java.io.IOException("no")).when(exclude).ensureExcluded(dir, "CLAUDE.local.md");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applier.apply(local("Alpha", "one"), dir)).isInstanceOf(java.io.IOException.class);

        assertThat(dir.resolve("CLAUDE.local.md")).doesNotExist();
    }

    @Test
    void localInstructionWithUnclosedLocalMarkersIsSkippedAndFileKept() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.local.md"), "<!-- cc-manager:begin alpha -->\nstuff\n");

        assertThat(applier.apply(local("Alpha", "one"), dir).status()).isEqualTo(OutcomeStatus.SKIPPED);
        assertThat(Files.readString(dir.resolve("CLAUDE.local.md"))).isEqualTo("<!-- cc-manager:begin alpha -->\nstuff\n");
    }

    @Test
    void localInstructionMovesTheBlockOutOfACrlfClaudeMd() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), ("# Mine\n\n" + ClaudeMdSections.block("Alpha", "one") + "\n").replace("\n", "\r\n"));

        applier.apply(local("Alpha", "one"), dir);

        assertThat(claudeMd()).doesNotContain("cc-manager:begin alpha").startsWith("# Mine");
        assertThat(Files.readString(dir.resolve("CLAUDE.local.md"))).contains("## Alpha");
    }
}
