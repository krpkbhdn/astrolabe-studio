package com.github.krpkbhdn.astrolabe.preset.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class InstructionApplierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @TempDir
    Path data;

    private InstructionApplier applier;

    @BeforeEach
    void setUp() {
        applier = OverlayInstructions.applier(data, 3, dir);
    }

    private static PresetComponent instruction(String title, String markdown) {
        return new PresetComponent(1, PresetComponentKind.INSTRUCTION, title, JSON.createObjectNode().put("markdown", markdown));
    }

    private Path overlayFile() {
        return data.resolve("projects").resolve("3").resolve("instructions.md");
    }

    private String overlay() throws Exception {
        return Files.readString(overlayFile());
    }

    @Test
    void sectionIsWrittenBetweenManagedMarkersOfItsSlugInTheOverlay() throws Exception {
        applier.apply(instruction("Code Style", "Be terse."), dir);

        assertThat(overlay()).contains("<!-- astrolabe:begin code-style -->").contains("## Code Style\nBe terse.").contains("<!-- astrolabe:end code-style -->");
        assertThat(dir.resolve("CLAUDE.md")).doesNotExist();
        assertThat(dir.resolve("CLAUDE.local.md")).doesNotExist();
    }

    @Test
    void blocksAreAppendedAndOnlyTheOwnBlockIsReplacedInPlace() throws Exception {
        applier.apply(instruction("Alpha", "one"), dir);
        applier.apply(instruction("Beta", "two"), dir);
        applier.apply(instruction("Alpha", "changed"), dir);

        assertThat(overlay()).contains("changed").doesNotContain("one\n").contains("## Beta\ntwo");
        assertThat(overlay().indexOf("## Alpha")).isLessThan(overlay().indexOf("## Beta"));
    }

    @Test
    void sameContentIsPresentAndFileUnchanged() throws Exception {
        applier.apply(instruction("Alpha", "one"), dir);
        var before = overlay();

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.PRESENT);
        applier.apply(instruction("Alpha", "one"), dir);

        assertThat(overlay()).isEqualTo(before);
    }

    @Test
    void changedContentIsPlannedAsUpdate() throws Exception {
        applier.apply(instruction("Alpha", "one"), dir);

        assertThat(applier.plan(instruction("Alpha", "two"), dir).action()).isEqualTo(ChangeAction.UPDATE);
    }

    @Test
    void theScopeFieldIsIgnoredAndBothScopesShareTheOverlayFile() throws Exception {
        var local = new PresetComponent(2, PresetComponentKind.INSTRUCTION, "Beta", JSON.createObjectNode().put("markdown", "two").put("scope", "local"));

        applier.apply(instruction("Alpha", "one"), dir);
        applier.apply(local, dir);

        assertThat(overlay()).contains("## Alpha").contains("## Beta");
        assertThat(dir.resolve("CLAUDE.local.md")).doesNotExist();
    }

    @Test
    void anOldBlockInClaudeMdIsNeverTouched() throws Exception {
        var old = "# Mine\n\n" + ClaudeMdSections.block("Alpha", "one") + "\n";
        Files.writeString(dir.resolve("CLAUDE.md"), old);

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.CREATE);
        applier.apply(instruction("Alpha", "one"), dir);

        assertThat(Files.readString(dir.resolve("CLAUDE.md"))).isEqualTo(old);
        assertThat(overlay()).contains("## Alpha");
    }

    @Test
    void handWrittenSectionInClaudeMdIsNoConflict() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "## Alpha\nmy rules\n");

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.CREATE);
        assertThat(applier.apply(instruction("Alpha", "one"), dir).status()).isEqualTo(OutcomeStatus.APPLIED);
        assertThat(Files.readString(dir.resolve("CLAUDE.md"))).isEqualTo("## Alpha\nmy rules\n");
    }

    @Test
    void unclosedMarkersInTheOverlayAreAConflictAndNotRewritten() throws Exception {
        Files.createDirectories(overlayFile().getParent());
        Files.writeString(overlayFile(), "<!-- astrolabe:begin alpha -->\nstuff\n");

        assertThat(applier.plan(instruction("Alpha", "one"), dir).action()).isEqualTo(ChangeAction.CONFLICT);
        assertThat(applier.apply(instruction("Alpha", "one"), dir).status()).isEqualTo(OutcomeStatus.SKIPPED);
        assertThat(overlay()).isEqualTo("<!-- astrolabe:begin alpha -->\nstuff\n");
    }

    @Test
    void aFolderWithoutARegisteredProjectFailsWithoutWriting() throws Exception {
        var other = Files.createDirectory(dir.resolve("other"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applier.apply(instruction("Alpha", "one"), other)).isInstanceOf(RuntimeException.class);
        assertThat(overlayFile()).doesNotExist();
    }
}
