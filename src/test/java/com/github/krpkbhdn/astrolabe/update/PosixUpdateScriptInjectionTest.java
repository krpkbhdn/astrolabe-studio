package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Paths and values with spaces, quotes, {@code $()} and backticks must travel as argv only, never as script text, and never be evaluated. */
class PosixUpdateScriptInjectionTest {

    private static final String HOSTILE = "a b $(touch PWNED_SUBST) `touch PWNED_TICK` 'q' \"dq\" ; touch PWNED_SEMI #";
    // Windows hosts mangle double quotes when a JDK process hands them to an MSYS sh, so the live run leaves them out
    private static final String NO_DQ = "a b $(touch PWNED_SUBST) `touch PWNED_TICK` 'q' ; touch PWNED_SEMI #";
    private static final String FILE_SAFE_HOSTILE = "app $(touch PWNED_SUBST) `touch PWNED_TICK` 'q' ; touch PWNED_SEMI #.sh";

    @TempDir
    Path temp;

    @Test
    void scriptTextNeverContainsAnyPathOrValueOnlyTheTrustedFragment() {
        var text = PosixUpdateScript.script("step probe true");

        assertThat(text).contains("step probe true").doesNotContain("PWNED").doesNotContain("@@PLATFORM_STEPS@@");
    }

    @Test
    void commandKeepsEveryHostileValueAsOneSeparateArgument() {
        var script = temp.resolve(FILE_SAFE_HOSTILE);
        var command = PosixUpdateScript.command(script, temp.resolve("up dates"), 42, temp.resolve(FILE_SAFE_HOSTILE), 60, 5, List.of(HOSTILE, "$(id)"), List.of());

        assertThat(command).contains(HOSTILE, "$(id)", script.toAbsolutePath().toString());
        assertThat(command.get(2)).doesNotContain("PWNED").doesNotContain(HOSTILE);
        assertThat(command).filteredOn(argument -> argument.contains("PWNED_SUBST")).hasSize(3);
    }

    @Test
    void runningTheScriptWithHostileArgumentsEvaluatesNothingAndPassesThemUnchanged() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        var updates = Files.createDirectories(temp.resolve("updates"));
        var scriptDir = Files.createDirectories(temp.resolve("astrolabe-update-script"));
        var app = temp.resolve(FILE_SAFE_HOSTILE);
        Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
        app.toFile().setExecutable(true);
        var fragment = "printf '%s' \"$1\" > \"$UPDATES/arg1\"\nprintf '%s' \"$2\" > \"$UPDATES/arg2\"\nstep probe true\n";
        var script = scriptDir.resolve("update.sh");
        Files.writeString(script, PosixUpdateScript.script(fragment), StandardCharsets.UTF_8);

        var command = new ArrayList<>(List.of("sh", posix(script), posix(updates), "999999", posix(app), "60", "5", "2", NO_DQ, "$(touch PWNED_ARG)"));
        var process = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        awaitFile(updates.resolve("relaunched"));

        assertThat(Files.readString(updates.resolve("arg1"))).isEqualTo(NO_DQ);
        assertThat(Files.readString(updates.resolve("arg2"))).isEqualTo("$(touch PWNED_ARG)");
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
        assertThat(Files.readString(updates.resolve("update.log"))).contains("step probe exit 0").doesNotContain("PWNED").doesNotContain("touch");
        try (var files = Files.walk(temp)) {
            assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith("PWNED"));
        }
        assertThat(scriptDir).doesNotExist();
    }

    @Test
    void failedStepStillRelaunchesAndReportsTheStepAndExit126MeansCancelled() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        assertThat(run("step install false", "FAILED install")).isTrue();
        assertThat(run("AUTH_STEP=1; step install sh -c 'exit 126'", "AUTH_CANCELLED")).isTrue();
        assertThat(run("step install sh -c 'exit 126'", "FAILED install")).isTrue();
    }

    @Test
    void theScriptFolderIsRemovedOnlyWhenItIsNamedLikeOurTempFolder() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        for (var name : new String[] {"astrolabe-update-x", "not-ours"}) {
            var updates = Files.createDirectories(temp.resolve("updates-" + name));
            var scriptDir = Files.createDirectories(temp.resolve(name));
            var app = temp.resolve("app-" + name + ".sh");
            Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
            app.toFile().setExecutable(true);
            var script = scriptDir.resolve("update.sh");
            Files.writeString(script, PosixUpdateScript.script("step probe true"));

            var process = new ProcessBuilder("sh", posix(script), posix(updates), "999999", posix(app), "60", "5", "0").directory(temp.toFile())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            awaitFile(updates.resolve("relaunched"));

            if (name.startsWith("astrolabe-update-")) {
                assertThat(scriptDir).doesNotExist();
            } else {
                assertThat(script).exists();
            }
        }
    }

    @Test
    void aProcessThatNeverExitsIsTermedThenKilledAfterTheBoundedWaitAndTheUpdateContinues() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        var updates = Files.createDirectories(temp.resolve("updates-wait"));
        var script = Files.createDirectories(temp.resolve("script-wait")).resolve("update.sh");
        var app = temp.resolve("app-wait.sh");
        Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
        app.toFile().setExecutable(true);
        Files.writeString(script, PosixUpdateScript.script("step probe true"));

        var process = new ProcessBuilder("sh", "-c", "sleep 300 & P=$!; sh \"$1\" \"$2\" \"$P\" \"$3\" 1 1 0", "x", posix(script), posix(updates), posix(app))
                .directory(new java.io.File(System.getProperty("java.io.tmpdir"))) // not the @TempDir: a lingering child would hold it as cwd and block its deletion on Windows
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(Files.readString(updates.resolve("update.log"))).contains("wait timeout, TERM");
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
    }

    @Test
    void rewritingTheScriptFileWhileItWaitsForThePidChangesNothingBecauseTheBodyIsOneParsedCompound() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        var updates = Files.createDirectories(temp.resolve("updates-rewrite"));
        var script = Files.createDirectories(temp.resolve("astrolabe-update-rewrite")).resolve("update.sh");
        var app = temp.resolve("app-rewrite.sh");
        Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
        app.toFile().setExecutable(true);
        var marker = updates.resolve("marker");
        Files.writeString(script, PosixUpdateScript.script("step mark sh -c 'echo original > \"$1\"' sh \"$1\"\n"));

        var process = new ProcessBuilder("sh", "-c", "sleep 3 & P=$!; sh \"$1\" \"$2\" \"$P\" \"$3\" 60 5 1 \"$4\"", "x", posix(script), posix(updates), posix(app),
                posix(marker)).directory(temp.toFile()).redirectErrorStream(true).start();
        Thread.sleep(1000);
        // many lines, so a shell that read the file lazily would land on one of them wherever it stood
        Files.writeString(script, ("echo hacked > '" + posix(marker) + "'\n").repeat(5000));
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        awaitFile(updates.resolve("relaunched"));

        assertThat(Files.readString(marker).strip()).isEqualTo("original");
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
    }

    @Test
    void theWholeScriptIsOneCompoundCommandEndingInExit() {
        var text = PosixUpdateScript.script("step probe true");

        assertThat(text).startsWith("#!/bin/sh\n{\n").endsWith("}; exit\n").doesNotContain("@@");
    }

    @Test
    void cleanupFoldersGoOnlyAfterASuccessfulUpdate() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        for (var success : new boolean[] {true, false}) {
            var updates = Files.createDirectories(temp.resolve("updates-clean-" + success));
            var leftover = Files.createDirectories(temp.resolve("leftover-" + success));
            Files.writeString(leftover.resolve("f"), "x");
            var script = Files.createDirectories(temp.resolve("script-clean-" + success)).resolve("update.sh");
            var app = temp.resolve("app-clean-" + success + ".sh");
            Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
            app.toFile().setExecutable(true);
            Files.writeString(script, PosixUpdateScript.script(success ?"step probe true" : "step probe false"));

            var process = new ProcessBuilder("sh", posix(script), posix(updates), "999999", posix(app), "60", "5", "0", posix(leftover)).directory(temp.toFile())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            awaitFile(updates.resolve("relaunched"));

            if (success) {
                assertThat(leftover).doesNotExist();
            } else {
                assertThat(leftover).exists();
            }
        }
    }

    private boolean run(String fragment, String expectedResult) throws Exception {
        var updates = Files.createDirectories(temp.resolve("updates-" + Math.abs(fragment.hashCode())));
        var scriptDir = Files.createDirectories(temp.resolve("script-" + Math.abs(fragment.hashCode())));
        var app = scriptDir.resolveSibling("app-" + Math.abs(fragment.hashCode()) + ".sh");
        Files.writeString(app, "#!/bin/sh\n: > \"" + posix(updates) + "/relaunched\"\n");
        app.toFile().setExecutable(true);
        var script = scriptDir.resolve("update.sh");
        Files.writeString(script, PosixUpdateScript.script(fragment));
        var process = new ProcessBuilder("sh", posix(script), posix(updates), "999999", posix(app), "60", "5", "0").directory(temp.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        awaitFile(updates.resolve("relaunched"));
        return Files.readString(updates.resolve("result")).strip().equals(expectedResult);
    }

    private static void awaitFile(Path file) throws InterruptedException {
        for (var i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(100);
        }
        assertThat(file).exists();
    }

    private static String posix(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static boolean shellAvailable() {
        try {
            return new ProcessBuilder("sh", "-c", "true").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
