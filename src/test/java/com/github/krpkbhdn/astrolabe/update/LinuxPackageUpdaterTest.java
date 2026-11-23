package com.github.krpkbhdn.astrolabe.update;

import static com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem.LINUX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.workspace.FakeHostEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxPackageUpdaterTest {

    private static final Path BIN = Path.of("/usr/bin");
    private static final Path HOME = Path.of("home", "me");
    private static final String SHA256SUM = "/usr/bin/sha256sum";
    private static final String HASH ="ab".repeat(32);

    @TempDir
    Path temp;

    private FakeHostEnvironment host(String... binaries) {
        var host = new FakeHostEnvironment(LINUX, HOME).withPath(BIN, HOME.resolve(".local/bin")).withFile(BIN.resolve("sha256sum"));
        for (var binary : binaries) {
            host.withFile(BIN.resolve(binary));
        }
        return host;
    }

    private LinuxPackageUpdater deb(FakeHostEnvironment host) {
        return new LinuxPackageUpdater.Deb(host, new AppProperties(temp.resolve("data")), TestUpdateProperties.defaults());
    }

    private LinuxPackageUpdater rpm(FakeHostEnvironment host) {
        return new LinuxPackageUpdater.Rpm(host, new AppProperties(temp.resolve("data")), TestUpdateProperties.defaults());
    }

    private static Installation installation(InstallKind kind) {
        return new Installation(kind, "x64", Path.of("/opt/astrolabe"), Path.of("/opt/astrolabe/bin/astrolabe"));
    }

    @Test
    void needsAuthenticationAndMapsOneKindPerBean() {
        assertThat(deb(host()).needsAuthentication()).isTrue();
        assertThat(deb(host()).kind()).isEqualTo(InstallKind.DEB);
        assertThat(rpm(host()).kind()).isEqualTo(InstallKind.RPM);
        assertThat(deb(host("apt-get", "pkexec")).supported(installation(InstallKind.RPM))).isFalse();
    }

    @Test
    void debNeedsAptGetAndAnAuthorisationRoute() {
        assertThat(deb(host("apt-get", "pkexec")).supported(installation(InstallKind.DEB))).isTrue();
        assertThat(deb(host("apt-get", "sudo", "xterm")).supported(installation(InstallKind.DEB))).isTrue();
        assertThat(deb(host("pkexec")).supported(installation(InstallKind.DEB))).isFalse();
        assertThat(deb(host("apt-get")).supported(installation(InstallKind.DEB))).isFalse();
        assertThat(deb(host("apt-get", "xterm")).supported(installation(InstallKind.DEB))).isFalse();
        assertThat(deb(host("apt-get", "sudo")).supported(installation(InstallKind.DEB))).isFalse();
    }

    @Test
    void failsClosedWithoutSha256sum() {
        var noSum = new FakeHostEnvironment(LINUX, HOME).withPath(BIN).withFile(BIN.resolve("apt-get")).withFile(BIN.resolve("pkexec"));

        assertThat(deb(noSum).supported(installation(InstallKind.DEB))).isFalse();
        assertThat(deb(noSum).plan(Path.of("/p.deb"), HASH)).isEmpty();
    }

    @Test
    void launchRefusesAPackageThatThisProcessDidNotPrepare() {
        assertThatThrownBy(() -> deb(host("apt-get", "pkexec")).launchAfterExit(Path.of("/tmp/astrolabe-update-1/astrolabe-studio-update.deb"),
                installation(InstallKind.DEB), "2.4.0")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rpmPrefersDnfOverZypperAndNeedsOne() {
        var both = rpm(host("dnf", "zypper", "pkexec")).plan(Path.of("/p.rpm"), HASH).orElseThrow();
        var zypper = rpm(host("zypper", "pkexec")).plan(Path.of("/p.rpm"), HASH).orElseThrow();

        assertThat(both.fragment()).contains("\"$5\" install -y\n").doesNotContain("zypper");
        assertThat(both.arguments().get(2)).endsWith("dnf");
        assertThat(zypper.fragment()).contains("\"$5\" --non-interactive install --allow-unsigned-rpm\n");
        assertThat(zypper.arguments().get(2)).endsWith("zypper");
        assertThat(rpm(host("pkexec")).supported(installation(InstallKind.RPM))).isFalse();
    }

    @Test
    void toolsComeOnlyFromSystemDirectoriesNeverFromUserBinOrPath() {
        var hijacked = new FakeHostEnvironment(LINUX, HOME).withPath(HOME.resolve(".local/bin"), BIN)
                .withFile(HOME.resolve(".local/bin/pkexec")).withFile(HOME.resolve(".local/bin/apt-get"));

        assertThat(deb(hijacked).supported(installation(InstallKind.DEB))).isFalse();
    }

    @Test
    void pkexecIsPreferredAndTheTerminalsFollowTheDocumentedOrderWithoutXTerminalEmulator() {
        var withPkexec = deb(host("apt-get", "pkexec", "sudo", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow();
        var gnome = deb(host("apt-get", "sudo", "gnome-terminal", "konsole", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow();
        var konsole = deb(host("apt-get", "sudo", "konsole", "x-terminal-emulator", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow();
        var xterm = deb(host("apt-get", "sudo", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow();

        assertThat(withPkexec.arguments().get(1)).endsWith("pkexec");
        assertThat(gnome.fragment()).contains("step install \"$6\" --wait -- /bin/sh -c \"$WRAP\" sh \"$UPDATES/terminal-status\" \"$7\" /bin/sh -c \"$PRIV\"");
        assertThat(konsole.fragment()).contains("step install \"$6\" --nofork -e /bin/sh -c \"$WRAP\"");
        assertThat(xterm.fragment()).contains("step install \"$6\" -e /bin/sh -c \"$WRAP\"").doesNotContain("pkexec");
        assertThat(gnome.fragment()).contains("sh \"$1\" \"$4\" \"$3\" \"$5\" install -y\n");
        assertThat(konsole.arguments().get(5)).endsWith("konsole");
        assertThat(xterm.arguments()).hasSize(7);
        assertThat(xterm.arguments().get(1)).isEmpty();
        assertThat(xterm.arguments().get(5)).endsWith("xterm");
        assertThat(xterm.arguments().get(6)).endsWith("sudo");
        assertThat(deb(host("apt-get", "sudo", "x-terminal-emulator")).supported(installation(InstallKind.DEB))).isFalse();
    }

    @Test
    void terminalWrapperKeepsTheWindowOpenOnFailureAndRecordsTheStatus() {
        var fragment = deb(host("apt-get", "sudo", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow().fragment();

        assertThat(fragment).contains("read -r _").contains("printf \"%s\\n\" \"$rc\" > \"$STATUS\"").contains("exit \"$rc\"");
    }

    @Test
    void pkexecExit127FallsBackToTheTerminalOrFailsWithStepPkexec() {
        var both = deb(host("apt-get", "pkexec", "sudo", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow().fragment();
        var onlyPkexec = deb(host("apt-get", "pkexec")).plan(Path.of("/p.deb"), HASH).orElseThrow().fragment();

        assertThat(both).contains("if [ \"$rc\" -eq 127 ]; then").contains("step install \"$6\" -e");
        assertThat(onlyPkexec).contains("if [ \"$rc\" -eq 127 ]; then\nSTEP=pkexec\nfi").doesNotContain("terminal-status\" \"$7\"");
    }

    @Test
    void fragmentUsesPositionalArgumentsGuardsTheAbsolutePathAndMapsPkexecExitCodes() {
        var plan = deb(host("apt-get", "pkexec")).plan(Path.of("/tmp/astrolabe-update-x/astrolabe-studio-update.deb"), HASH).orElseThrow();
        var fragment = plan.fragment();

        assertThat(fragment).contains("case \"$1\" in /*) ;; *) STEP=check; return 1 ;; esac")
                .contains("step install \"$2\" /bin/sh -c \"$PRIV\" sh \"$1\" \"$4\" \"$3\" \"$5\" install -y\n").doesNotContain("126")
                .doesNotContain("astrolabe-studio-update").doesNotContain("/usr/bin/").doesNotContain(".deb").doesNotContain(HASH);
        assertThat(fragment).contains("rm -rf \"$(dirname \"$1\")\"");
        assertThat(plan.arguments().get(0)).endsWith("astrolabe-studio-update.deb");
        assertThat(plan.arguments().get(3)).isEqualTo(HASH);
        assertThat(plan.arguments().get(4)).endsWith("sha256sum");
        // inside the privileged script: root-owned folder, copy, hash check strictly before the install
        assertThat(fragment).contains("DIR=$(mktemp -d)").contains("chmod 755 \"$DIR\"").contains("cp -- \"$PKG\" \"$COPY\"")
                .contains("[ \"$ACTUAL\" = \"$SUM\" ] || exit 3").contains("trap cleanup EXIT");
        assertThat(fragment.indexOf("exit 3")).isLessThan(fragment.indexOf("\"$MGR\" \"$@\" \"$COPY\""));
        assertThat(fragment.indexOf("cp -- ")).isLessThan(fragment.indexOf("exit 3"));
    }

    @Test
    void prepareCopiesIntoAFreshFolderUnderAFixedNameAndKeepsTheContent() throws Exception {
        var source = Files.writeString(temp.resolve("Evil Name; rm -rf x.deb"), "package bytes");
        var updater = new LinuxPackageUpdater(InstallKind.DEB, ".deb", List.of(), host(), new AppProperties(temp), temp.resolve("tmp"), TestUpdateProperties.defaults());
        Files.createDirectory(temp.resolve("tmp"));

        var first = updater.prepare(source, sha256Of("package bytes"), installation(InstallKind.DEB));
        var second = updater.prepare(source, sha256Of("package bytes"), installation(InstallKind.DEB));

        assertThat(first.getFileName().toString()).isEqualTo("astrolabe-studio-update.deb");
        assertThat(first.getParent()).isNotEqualTo(second.getParent());
        assertThat(first.getParent().getParent()).isEqualTo(temp.resolve("tmp"));
        assertThat(first.getParent().getFileName().toString()).startsWith("astrolabe-update-");
        assertThat(Files.readString(first)).isEqualTo("package bytes");
        assertThat(Files.readString(source)).isEqualTo("package bytes");
        if (first.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(first))).isEqualTo("rw-r--r--");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(first.getParent()))).isEqualTo("rwxr-xr-x");
        }
    }

    @Test
    void prepareLeavesNothingBehindWhenTheSourceIsMissing() throws IOException {
        var root = Files.createDirectory(temp.resolve("tmp"));
        var updater = new LinuxPackageUpdater(InstallKind.RPM, ".rpm", List.of(), host(), new AppProperties(temp), root, TestUpdateProperties.defaults());

        assertThatThrownBy(() -> updater.prepare(temp.resolve("missing.rpm"), HASH, installation(InstallKind.RPM))).isInstanceOf(IOException.class);
        try (var children = Files.list(root)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void generatedScriptRunsPkexecWithApt() throws Exception {
        var log = temp.resolve("argv.log");
        var pkexec = executable("pkexec", "printf 'pkexec' >> '" + msys(log) + "'; for a in \"$@\"; do printf ' [%s]' \"$a\" >> '" + msys(log) + "'; done; echo >> '" + msys(log) + "'\nexec \"$@\"");
        var apt = executable("apt-get", "printf 'apt' >> '" + msys(log) + "'; for a in \"$@\"; do printf ' [%s]' \"$a\" >> '" + msys(log) + "'; done; echo >> '" + msys(log) + "'\nexit 0");
        var app = executable("app", "exit 0");
        var packageDirectory = Files.createDirectory(temp.resolve("astrolabe-update-1"));
        var pkg = Files.writeString(packageDirectory.resolve("astrolabe-studio-update.deb"), "x");
        var plan = deb(host("apt-get", "pkexec")).plan(pkg, sha256Of("x")).orElseThrow();

        var result = run(plan.fragment(), app, List.of(msys(pkg), msys(pkexec), msys(apt), sha256Of("x"), SHA256SUM, "", ""));

        // the logged pkexec argv spans lines (the script is one argument), so check the whole text
        var text = Files.readString(log);
        assertThat(text).startsWith("pkexec [/bin/sh] [-c] [").contains("] [sh] [" + msys(pkg) + "] [" + sha256Of("x") + "] [" + msys(apt) + "] [" + SHA256SUM
                + "] [install] [-y]\n");
        assertThat(text).matches("(?s).*\napt \\[install] \\[-y] \\[[^\\]]*/astrolabe-studio-update\\.deb]\n").doesNotContain("apt [install] [-y] [" + msys(pkg) + "]");
        assertThat(result).isEqualTo("OK");
        assertThat(packageDirectory).doesNotExist();
    }

    @Test
    void generatedScriptRefusesAPackageThatDiffersFromTheVerifiedHashAndNeverCallsTheManager() throws Exception {
        var log = temp.resolve("argv.log");
        var pkexec = executable("pkexec", "exec \"$@\"");
        var apt = executable("apt-get", "echo called >> '" + msys(log) + "'\nexit 0");
        var app = executable("app", "exit 0");
        var packageDirectory = Files.createDirectory(temp.resolve("astrolabe-update-1"));
        var pkg = Files.writeString(packageDirectory.resolve("astrolabe-studio-update.deb"), "swapped");
        var plan = deb(host("apt-get", "pkexec")).plan(pkg, sha256Of("x")).orElseThrow();

        var result = run(plan.fragment(), app, List.of(msys(pkg), msys(pkexec), msys(apt), sha256Of("x"), SHA256SUM, "", ""));

        assertThat(result).isEqualTo("FAILED install");
        assertThat(log).doesNotExist();
        assertThat(packageDirectory).doesNotExist();
    }

    @Test
    void terminalWrapperContainsNoSingleQuoteBecauseItIsSplicedIntoASingleQuotedWord() {
        assertThat(LinuxPackageUpdater.TERMINAL_WRAPPER).doesNotContain("'");
        assertThat(LinuxPackageUpdater.PRIVILEGED_INSTALL).doesNotContain("'");
    }

    @Test
    void theSplicedWrapperWritesExactlyTheExitNumberToTheStatusFile() throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        var fragment = deb(host("apt-get", "sudo", "xterm")).plan(Path.of("/p.deb"), HASH).orElseThrow().fragment();
        var definitions = fragment.substring(0, fragment.indexOf("AUTH_STEP="));
        for (var code : new int[] {0, 5}) {
            var status = temp.resolve("status-" + code);
            var script = temp.resolve("wrapper-" + code + ".sh");
            Files.writeString(script, "f() {\n" + definitions + "}\nf /x\nsh -c \"$WRAP\" sh " + msys(status) + " sh -c 'exit " + code + "'\necho \"rc=$?\"\n");
            var process = new ProcessBuilder("sh", msys(script)).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();

            assertThat(Files.readString(status)).isEqualTo(code + "\n");
            assertThat(output).contains("rc=" + code);
        }
    }

    @Test
    void prepareRejectsASourceWhoseBytesDifferFromTheVerifiedHashAndLeavesNothing() throws IOException {
        var root = Files.createDirectory(temp.resolve("tmp"));
        var source = Files.writeString(temp.resolve("swapped.deb"), "malicious");
        var updater = new LinuxPackageUpdater(InstallKind.DEB, ".deb", List.of(), host(), new AppProperties(temp), root, TestUpdateProperties.defaults());

        assertThatThrownBy(() -> updater.prepare(source, HASH, installation(InstallKind.DEB))).isInstanceOf(IOException.class).hasMessageContaining("differs");
        try (var children = Files.list(root)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void exit126OnTheTerminalRouteIsAFailureNotACancellation() throws Exception {
        var sudo = executable("sudo", "exit 126");
        var terminal = executable("terminal", "while [ \"$1\" != /bin/sh ]; do shift; done\nexec \"$@\"");
        var pkexec = executable("pkexec", "exit 127");
        var pkg = Files.writeString(Files.createDirectory(temp.resolve("astrolabe-update-1")).resolve("astrolabe-studio-update.deb"), "x");
        var plan = deb(host("apt-get", "pkexec", "sudo", "xterm")).plan(pkg, sha256Of("x")).orElseThrow();

        var result = run(plan.fragment(), executable("app", "exit 0"),
                List.of(msys(pkg), msys(pkexec), msys(executable("apt-get", "exit 0")), sha256Of("x"), SHA256SUM, msys(terminal), msys(sudo)));

        assertThat(result).isEqualTo("FAILED install");
    }

    @Test
    void zypperExit102And103CountAsSuccessButOtherCodesAndOtherManagersDoNot() throws Exception {
        var pkexec = executable("pkexec", "exec \"$@\"");
        var app = executable("app", "exit 0");
        var pkg = Files.writeString(Files.createDirectory(temp.resolve("astrolabe-update-1")).resolve("astrolabe-studio-update.rpm"), "x");
        var zypperPlan = rpm(host("zypper", "pkexec")).plan(pkg, sha256Of("x")).orElseThrow();
        var dnfPlan = rpm(host("dnf", "pkexec")).plan(pkg, sha256Of("x")).orElseThrow();

        assertThat(managerResult(zypperPlan, pkexec, app, pkg, 102)).isEqualTo("OK");
        assertThat(managerResult(zypperPlan, pkexec, app, pkg, 103)).isEqualTo("OK");
        assertThat(managerResult(zypperPlan, pkexec, app, pkg, 104)).isEqualTo("FAILED install");
        assertThat(managerResult(zypperPlan, pkexec, app, pkg, 1)).isEqualTo("FAILED install");
        assertThat(managerResult(dnfPlan, pkexec, app, pkg, 102)).isEqualTo("FAILED install");
    }

    private String managerResult(LinuxPackageUpdater.Plan plan, Path pkexec, Path app, Path pkg, int code) throws Exception {
        Files.createDirectories(pkg.getParent());
        Files.writeString(pkg, "x");
        var manager = executable("manager-" + code, "exit " + code);
        return run(plan.fragment(), app, List.of(msys(pkg), msys(pkexec), msys(manager), sha256Of("x"), SHA256SUM, "", ""));
    }

    @Test
    void aDiscardedOrLaunchedPackageIsForgottenSoItCannotBeLaunchedAgain() throws Exception {
        var root = Files.createDirectory(temp.resolve("tmp"));
        var updater = new LinuxPackageUpdater(InstallKind.DEB, ".deb", List.of(new LinuxPackageUpdater.Manager("apt-get", "install -y")), host("apt-get", "pkexec"),
                new AppProperties(temp), root, TestUpdateProperties.defaults());
        var source = Files.writeString(temp.resolve("p.deb"), "x");
        var prepared = updater.prepare(source, sha256Of("x"), installation(InstallKind.DEB));

        updater.discard(prepared);

        assertThatThrownBy(() -> updater.launchAfterExit(prepared, installation(InstallKind.DEB), "2.4.0")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not prepared");
    }

    private static String sha256Of(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void generatedScriptMapsDismissedAuthorisationAndRejectsRelativePaths() throws Exception {
        var pkexec = executable("pkexec", "exit 126");
        var apt = executable("apt-get", "exit 0");
        var app = executable("app", "exit 0");
        var plan = deb(host("apt-get", "pkexec")).plan(Path.of("/p.deb"), HASH).orElseThrow();

        assertThat(run(plan.fragment(), app, List.of("/astrolabe-update-1/p.deb", msys(pkexec), msys(apt), HASH, SHA256SUM, "", ""))).isEqualTo("AUTH_CANCELLED");
        assertThat(run(plan.fragment(), app, List.of("relative.deb", msys(pkexec), msys(apt), HASH, SHA256SUM, "", ""))).isEqualTo("FAILED check");
    }

    @Test
    void pkexecExit127IsNotACancellationItFallsBackToTheTerminalAndInstalls() throws Exception {
        var pkexec = executable("pkexec", "exit 127");
        var apt = executable("apt-get", "exit 0");
        var app = executable("app", "exit 0");
        var terminal = executable("terminal", "while [ \"$1\" != /bin/sh ]; do shift; done\nexec \"$@\"");
        var sudo = executable("sudo", "exec \"$@\"");
        var pkg = Files.writeString(Files.createDirectory(temp.resolve("astrolabe-update-1")).resolve("astrolabe-studio-update.deb"), "x");
        var plan = deb(host("apt-get", "pkexec", "sudo", "xterm")).plan(pkg, sha256Of("x")).orElseThrow();

        var result = run(plan.fragment(), app, List.of(msys(pkg), msys(pkexec), msys(apt), sha256Of("x"), SHA256SUM, msys(terminal), msys(sudo)));

        assertThat(result).isEqualTo("OK");
    }

    @Test
    void pkexecExit127WithoutATerminalFailsWithStepPkexec() throws Exception {
        var plan = deb(host("apt-get", "pkexec")).plan(Path.of("/p.deb"), HASH).orElseThrow();

        var result = run(plan.fragment(), executable("app", "exit 0"), List.of("/astrolabe-update-1/p.deb", executable("pkexec", "exit 127").toString(),
                "/usr/bin/apt-get", HASH, SHA256SUM, "", ""));

        assertThat(result).isEqualTo("FAILED pkexec");
    }

    @Test
    void terminalRouteReportsTheRealFailureStatusNotTheEmulatorsAndAnEmulatorThatRunsNothingFails() throws Exception {
        var sudo = executable("sudo", "exec \"$@\"");
        var terminal = executable("terminal", "while [ \"$1\" != /bin/sh ]; do shift; done\nexec \"$@\"");
        var lyingTerminal = executable("lying", "exit 0");
        var apt = executable("apt-get", "exit 5");
        var pkg = Files.writeString(Files.createDirectory(temp.resolve("astrolabe-update-1")).resolve("astrolabe-studio-update.deb"), "x");
        var plan = deb(host("apt-get", "sudo", "xterm")).plan(pkg, sha256Of("x")).orElseThrow();
        var app = executable("app", "exit 0");

        assertThat(run(plan.fragment(), app, List.of(msys(pkg), "", msys(apt), sha256Of("x"), SHA256SUM, msys(terminal), msys(sudo))))
                .isEqualTo("FAILED install");
        assertThat(run(plan.fragment(), app, List.of(msys(pkg), "", msys(apt), sha256Of("x"), SHA256SUM, msys(lyingTerminal), msys(sudo))))
                .isEqualTo("FAILED install");
    }

    private Path executable(String name, String body) throws IOException {
        var file = temp.resolve("fake-" + name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file;
    }

    /** The path as an MSYS/POSIX shell sees it (Git Bash maps C:\x to /c/x); unchanged on Linux and macOS. */
    private static String msys(Path path) {
        var text = path.toAbsolutePath().toString().replace('\\', '/');
        return text.length() > 2 && text.charAt(1) == ':' ? "/" + Character.toLowerCase(text.charAt(0)) + text.substring(2) : text;
    }

    private String run(String fragment, Path app, List<String> arguments) throws Exception {
        assumeTrue(shellAvailable(), "no sh on this host");
        var updates = Files.createDirectories(temp.resolve("updates"));
        Files.deleteIfExists(updates.resolve("result"));
        var script = Files.writeString(temp.resolve("update.sh"), PosixUpdateScript.script(fragment));
        var command = ScriptArgs.command(msys(script), msys(updates), "2147483646", msys(app), arguments);
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return Files.readString(updates.resolve("result")).strip();
    }

    private static boolean shellAvailable() {
        try {
            return new ProcessBuilder("sh", "-c", "true").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
