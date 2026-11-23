package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Linux relaunch (gtk-launch, then direct, verified alive) and the desktop notifications, with fake tools first on PATH. */
class PosixUpdateScriptLinuxTest {

    private static final String DESKTOP_ID = "fake-id";
    private static final int WAIT_SECONDS = 60;

    @TempDir
    Path temp;

    private Path updates;
    private Path bin;
    private Path stop;
    private Path app;
    private Path notifyLog;
    private Path gtkLog;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(toolsAvailable(), "no sh with a working ps on this host");
        updates = Files.createDirectories(temp.resolve("updates"));
        bin = Files.createDirectories(temp.resolve("bin"));
        stop = temp.resolve("stop");
        notifyLog = temp.resolve("notify.log");
        gtkLog = temp.resolve("gtk.log");
        app = tool(temp.resolve("app.sh"), "printf started >> '" + posix(temp.resolve("started")) + "'\nwhile [ ! -f '" + posix(stop) + "' ]; do sleep 1; done");
        tool(bin.resolve("notify-send"), "printf '%s\\n' \"$*\" >> '" + posix(notifyLog) + "'\ncase \" $* \" in *\" -p \"*) echo 7 ;; esac");
    }

    @AfterEach
    void stopApp() throws IOException {
        if (stop != null) {
            Files.writeString(stop, "x");
        }
    }

    @Test
    void gtkLaunchStartsTheAppWithTheDesktopIdAndTheUpdateIsAnnouncedAndConfirmed() throws Exception {
        tool(bin.resolve("gtk-launch"), "printf '%s\\n' \"$1\" >> '" + posix(gtkLog) + "'\n'" + posix(app) + "' &");

        run(PosixUpdateScript.Notices.linux("9.9.9", DESKTOP_ID, false), "step probe true");

        assertThat(Files.readString(gtkLog).strip()).isEqualTo(DESKTOP_ID);
        assertThat(Files.readString(updates.resolve("update.log"))).contains("update to 9.9.9", "relaunch gtk-launch", "relaunch alive").doesNotContain("relaunch failed");
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
        assertThat(Files.readString(notifyLog)).contains("Updating Astrolabe Studio to 9.9.9", "Installing 9.9.9", "Starting Astrolabe Studio 9.9.9",
                "Astrolabe Studio 9.9.9 is installed and starting").contains("-r 7");
    }

    @Test
    void whenNoLauncherWorksTheAppIsStartedDirectlyAndTheFirstFailureIsLogged() throws Exception {
        tool(bin.resolve("gtk-launch"), "exit 1");
        tool(bin.resolve("systemd-run"), "exit 1");

        run(PosixUpdateScript.Notices.linux("9.9.9", DESKTOP_ID, false), "step probe true");

        assertThat(Files.readString(updates.resolve("update.log"))).contains("relaunch not alive, retry direct", "relaunch alive after retry");
        assertThat(temp.resolve("started")).exists();
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
    }

    @Test
    void anAppThatDiesAtOnceIsReportedWithTheRelaunchLogAndNeverChangesTheResult() throws Exception {
        var dying = tool(temp.resolve("dying.sh"), "echo boom-from-app >&2\nexit 1");
        tool(bin.resolve("gtk-launch"), "exit 1");
        tool(bin.resolve("systemd-run"), "exit 1");

        run(PosixUpdateScript.Notices.linux("9.9.9", DESKTOP_ID, true), dying, "step probe true");

        assertThat(Files.readString(updates.resolve("update.log"))).contains("relaunch failed", "boom-from-app");
        assertThat(Files.readString(updates.resolve("relaunch.log"))).contains("boom-from-app");
        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("OK");
        assertThat(Files.readString(notifyLog)).contains("did not start", "relaunch.log", "A system dialog will ask for your password");
    }

    @Test
    void aFailedUpdateIsAnnouncedWithTheLogNameAndStillRestartsTheOldApp() throws Exception {
        tool(bin.resolve("gtk-launch"), "'" + posix(app) + "' &");

        run(PosixUpdateScript.Notices.linux("9.9.9", DESKTOP_ID, false), "step install false");

        assertThat(Files.readString(updates.resolve("result")).strip()).isEqualTo("FAILED install");
        assertThat(Files.readString(notifyLog)).contains("was not installed. See " + posix(updates) + "/update.log").doesNotContain("is installed and starting");
        assertThat(temp.resolve("started")).exists();
    }

    @Test
    void withoutADesktopIdNothingIsNotifiedAndTheAppIsStartedPlainly() throws Exception {
        run(PosixUpdateScript.Notices.NONE, "step probe true");

        assertThat(notifyLog).doesNotExist();
        assertThat(Files.readString(updates.resolve("update.log"))).doesNotContain("relaunch alive");
    }

    @Test
    void theRelaunchedAppDoesNotInheritTheJpackageLauncherVariable() throws Exception {
        var recorded = temp.resolve("launcher-variable");
        var probe = tool(temp.resolve("probe.sh"), "printf '%s' \"${_JPACKAGE_LAUNCHER-unset}\" > '" + posix(recorded) + "'");

        run(PosixUpdateScript.Notices.NONE, probe, "step probe true", Map.of(PosixUpdateScript.JPACKAGE_LAUNCHER_VARIABLE, "0"));
        for (var i = 0; i < 100 && !Files.exists(recorded); i++) {
            Thread.sleep(100);
        }

        assertThat(Files.readString(recorded)).isEqualTo("unset");
    }

    private void run(PosixUpdateScript.Notices notices, String fragment) throws Exception {
        run(notices, app, fragment);
    }

    private void run(PosixUpdateScript.Notices notices, Path launched, String fragment) throws Exception {
        run(notices, launched, fragment, Map.of());
    }

    private void run(PosixUpdateScript.Notices notices, Path launched, String fragment, Map<String, String> environment) throws Exception {
        var script = Files.writeString(Files.createDirectories(temp.resolve("script")).resolve("update.sh"), PosixUpdateScript.script(fragment));
        var command = ScriptArgs.command(posix(script), posix(updates), "999999", posix(launched), new PosixUpdateScript.Timing(WAIT_SECONDS, 5, 0, 1), notices, List.of());
        var builder = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PATH", posix(bin) + ":" + System.getenv("PATH"));
        builder.environment().putAll(environment);
        var process = builder.start();
        process.getOutputStream().close();
        assertThat(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        awaitFile(notices.desktopId().isEmpty() ? updates.resolve("update.log") : updates.resolve("relaunch.log"));
    }

    private static void awaitFile(Path file) throws InterruptedException {
        for (var i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(100);
        }
        assertThat(file).exists();
    }

    private static Path tool(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static String posix(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static boolean toolsAvailable() {
        try {
            var process = new ProcessBuilder("sh", "-c", "sleep 5 & p=$!; ps -eo args= | grep -c '^sleep 5'; kill $p").redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            return process.waitFor(10, TimeUnit.SECONDS) && output.strip().equals("1");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
