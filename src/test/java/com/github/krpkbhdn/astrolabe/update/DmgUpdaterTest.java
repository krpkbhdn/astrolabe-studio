package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DmgUpdaterTest {

    @TempDir
    Path temp;

    private final List<List<String>> commands = new ArrayList<>();
    private List<String> appsOnImage = List.of("Astrolabe Studio.app");
    private boolean failAttach;
    private boolean failCopy;
    private boolean failPlainDetach;
    private boolean failAllDetach;
    private String stagedBundleId = "com.example.astrolabe";
    private String dmgHash;
    private Path bundle;
    private Installation installation;
    private DmgUpdater updater;

    @BeforeEach
    void setUp() throws IOException {
        bundle = Files.createDirectories(temp.resolve("Applications").resolve("Astrolabe Studio.app"));
        installation = new Installation(InstallKind.DMG, "arm64", bundle, bundle.resolve("Contents/MacOS/astrolabe"));
        updater = new DmgUpdater(temp.resolve("updates"), this::fake, TestUpdateProperties.defaults(), temp.resolve("work"));
        Files.createDirectories(temp.resolve("work"));
        Files.writeString(temp.resolve("a.dmg"), "dmg bytes");
        dmgHash = UpdateFiles.sha256(temp.resolve("a.dmg"));
    }

    private CommandRunner.Result fake(List<String> command, java.time.Duration timeout) throws IOException {
        commands.add(command);
        switch (command.get(0) + " " + command.get(1)) {
            case "/usr/bin/defaults read" -> {
                var id = command.get(2).contains(".update") ? stagedBundleId : "com.example.astrolabe";
                return new CommandRunner.Result(0, id + "\n", "", false);
            }
            case "/usr/bin/hdiutil attach" -> {
                if (failAttach) {
                    return new CommandRunner.Result(1, "", "", false);
                }
                var mount = Path.of(command.get(command.indexOf("-mountpoint") + 1));
                assertThat(mount).isDirectory();
                for (var app : appsOnImage) {
                    Files.createDirectories(mount.resolve(app));
                }
            }
            case "/usr/bin/hdiutil detach" -> {
                if (failAllDetach || failPlainDetach && !command.contains("-force")) {
                    return new CommandRunner.Result(1, "", "busy", false);
                }
            }
            default -> {
                if (failCopy) {
                    return new CommandRunner.Result(1, "", "", false);
                }
            }
        }
        return new CommandRunner.Result(0, "", "", false);
    }

    private List<String> verbs() {
        return commands.stream().map(c -> c.get(0).substring(c.get(0).lastIndexOf('/') + 1) + (c.get(0).endsWith("hdiutil") || c.get(0).endsWith("defaults") ? " " + c.get(1) : "")).toList();
    }

    @Test
    void mountsCopiesWithDittoAndDetaches() throws IOException {
        var staged = updater.prepare(temp.resolve("a.dmg"), dmgHash, installation);

        assertThat(verbs()).containsExactly("hdiutil attach", "ditto", "defaults read", "defaults read", "hdiutil detach");
        assertThat(commands.get(0).getFirst()).isEqualTo("/usr/bin/hdiutil");
        assertThat(commands.get(1).getFirst()).isEqualTo("/usr/bin/ditto");
        assertThat(commands.get(0)).contains("-readonly", "-nobrowse", "-noautoopen", "-mountpoint").endsWith(temp.resolve("a.dmg").toAbsolutePath().toString());
        assertThat(staged).isEqualTo(bundle.toAbsolutePath().resolveSibling(".Astrolabe Studio.app.update"));
        assertThat(commands.get(1).get(1)).endsWith("Astrolabe Studio.app");
        assertThat(commands.get(1).get(2)).isEqualTo(staged.toString());
        assertThat(Path.of(commands.get(0).get(commands.get(0).indexOf("-mountpoint") + 1)).startsWith(temp.resolve("work"))).isTrue();
    }

    @Test
    void refusesADiskImageThatDiffersFromTheVerifiedHashBeforeMountingIt() throws IOException {
        Files.writeString(temp.resolve("a.dmg"), "swapped after verification");

        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).isInstanceOf(IOException.class).hasMessageContaining("differs");

        assertThat(commands).isEmpty();
    }

    @Test
    void rejectsAStagedAppWithAnotherBundleIdentifierAndDeletesIt() {
        stagedBundleId = "com.evil.app";

        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).hasMessageContaining("bundle identifier");

        assertThat(bundle.resolveSibling(".Astrolabe Studio.app.update")).doesNotExist();
        assertThat(verbs()).last().isEqualTo("hdiutil detach");
    }

    @Test
    void keepsTheWorkFolderOutsideUpdatesWhenTheImageCannotBeDetached() throws IOException {
        failAllDetach = true;

        updater.prepare(temp.resolve("a.dmg"), dmgHash, installation);

        assertThat(verbs()).endsWith("hdiutil detach", "hdiutil detach");
        assertThat(temp.resolve("updates")).isEmptyDirectory();
        try (var children = Files.list(temp.resolve("work"))) {
            assertThat(children).hasSize(1);
        }
    }

    @Test
    void detachesAlsoWhenTheCopyFails() {
        failCopy = true;

        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).isInstanceOf(IOException.class);

        assertThat(verbs()).containsExactly("hdiutil attach", "ditto", "hdiutil detach");
    }

    @Test
    void forcesTheDetachOnceWhenThePlainOneFails() throws IOException {
        failPlainDetach = true;

        updater.prepare(temp.resolve("a.dmg"), dmgHash, installation);

        assertThat(commands.getLast()).contains("detach", "-force");
        assertThat(verbs()).containsExactly("hdiutil attach", "ditto", "defaults read", "defaults read", "hdiutil detach", "hdiutil detach");
    }

    @Test
    void doesNotDetachWhenTheMountFailed() {
        failAttach = true;

        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).isInstanceOf(IOException.class);

        assertThat(verbs()).containsExactly("hdiutil attach");
    }

    @Test
    void rejectsAnImageWithoutExactlyOneApp() {
        appsOnImage = List.of("Astrolabe Studio.app", "Other.app");
        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).hasMessageContaining("exactly one app");
        assertThat(verbs()).containsExactly("hdiutil attach", "hdiutil detach");

        appsOnImage = List.of();
        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).hasMessageContaining("exactly one app");
    }

    @Test
    void rejectsAnAppWithAnotherName() {
        appsOnImage = List.of("Evil.app");

        assertThatThrownBy(() -> updater.prepare(temp.resolve("a.dmg"), dmgHash, installation)).hasMessageContaining("not Astrolabe Studio.app");

        assertThat(verbs()).containsExactly("hdiutil attach", "hdiutil detach");
    }

    @Test
    void fragmentUsesPositionalArgumentsOnlyAndRestoresTheOldBundle() {
        var fragment = DmgUpdater.FRAGMENT;

        assertThat(fragment).contains("\"$1\"", "\"$2\"").doesNotContain(temp.toString()).doesNotContain("Astrolabe");
        assertThat(fragment).contains("step backup mv \"$1\" \"$old\"").contains("step install mv \"$2\" \"$1\"")
                .contains("step restore mv \"$old\" \"$1\"").contains("STEP=install").contains("step cleanup rm -rf \"$old\"");
        assertThat(fragment.indexOf("step install")).isLessThan(fragment.indexOf("step restore"));
    }

    @Test
    void isSupportedOnlyForADmgInstallationInAWritableFolder() {
        assertThat(updater.supported(installation)).isTrue();
        assertThat(updater.supported(new Installation(InstallKind.MSI, "x64", bundle, bundle))).isFalse();
        assertThat(updater.supported(new Installation(InstallKind.DMG, "x64", temp.resolve("missing/Gone.app"), temp.resolve("missing/Gone.app")))).isFalse();
        assertThat(updater.needsAuthentication()).isFalse();
    }
}
