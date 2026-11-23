package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Installs a verified .deb or .rpm with the distribution's package manager after this process has exited. This is a root install path:
 * the tools are taken only from fixed system directories (never from PATH or user bin directories, which a user-writable
 * file could hijack), the package is a copy under a fixed name in a fresh folder, and no name ever becomes script text.
 *
 * <p>Flags: {@code apt-get install -y <abs path>} (apt treats an argument with a leading slash as a file); {@code dnf install -y}
 * needs no signature flag because {@code localpkg_gpgcheck} is off by default in dnf and dnf5 (the file is already checked against
 * the bundled release key); {@code zypper --non-interactive install --allow-unsigned-rpm} installs a local rpm without a distro
 * signature silently (documented zypper install option).
 *
 * <p>pkexec exits 126 for a dismissed dialog (reported as cancelled) and 127 when there is no usable polkit agent: that falls back to the
 * terminal route, or fails with step {@code pkexec} when there is none.
 *
 * <p>zypper exits 102 or 103 (reboot or restart needed) when the install itself succeeded: those two count as success for zypper only; dnf
 * and apt-get exit codes are taken as they are.
 *
 * <p>Privileged text: it is a constant and only ever an {@code sh -c} argument (to pkexec, or through the terminal wrapper to sudo), never
 * read from a file at run time, and the generated {@code update.sh} is one compound command the shell has parsed completely before the
 * waiting for the pid and the dialog begin, so rewriting that file afterwards changes nothing. The remaining boundary is the user account:
 * code running as the same user can still signal or ptrace the waiting shell and change the arguments it passes on; the root step
 * itself only trusts its own constant text, hashes its own root-owned copy and installs only that copy.
 *
 * <p>Terminal route: the privileged script travels as an {@code sh -c} argument. Only emulators that forward argv unchanged are used: {@code gnome-terminal --wait -- cmd args}, {@code konsole
 * --nofork -e cmd args} and {@code xterm -e cmd args} (per their manuals everything after {@code --} or {@code -e} is the program and its
 * own arguments); {@code x-terminal-emulator} is out because the {@code -e} of several emulators behind it takes one string. A constant
 * wrapper keeps the window open after a failure and writes the status of the sudo command to a status file, which the script prefers over
 * the emulator's own exit status (xterm documents passing the child's status on; that gnome-terminal and konsole do so in every version is
 * unverified here); no status file with a zero exit counts as a failure. After a failure the wrapper keeps the window open until the user
 * presses Enter (also for a zypper 102 or 103, which the script then still counts as success). The terminal table and the shell texts are static data and stay
 * constants.
 */
class LinuxPackageUpdater implements PlatformUpdater {

    /** @param installArgs trusted constant words between the binary and the package
     *  @param successCodes trusted shell case pattern of extra exit codes that mean success (e.g. {@code 102|103}), empty for none */
    record Manager(String binary, String installArgs, String successCodes) {

        Manager(String binary, String installArgs) {
            this(binary, installArgs, "");
        }
    }

    /** @param flags trusted constant words between the terminal binary and the command it runs (waiting for it to finish) */
    record Terminal(String binary, String flags) {}

    /**
     * The script fragment and its seven positional arguments: package, pkexec (or empty), manager, SHA-256, sha256sum, terminal (or empty), sudo
     * (or empty).
     */
    record Plan(String fragment, List<String> arguments) {}

    private record Found(String path, Terminal terminal) {}

    private static final List<String> TRUSTED_DIRECTORIES = List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin");
    private static final List<Terminal> TERMINALS = List.of(new Terminal("gnome-terminal", "--wait --"), new Terminal("konsole", "--nofork -e"),
            new Terminal("xterm", "-e"));
    /** The menu entry the jpackage deb and rpm install, which gtk-launch starts. */
    static final String DESKTOP_ID = "astrolabe-studio-Astrolabe_Studio";
    private static final String PKEXEC = "pkexec";
    private static final String SUDO = "sudo";
    private static final String UPDATES_DIRECTORY = "updates";
    /** ZYPPER_EXIT_INF_REBOOT_NEEDED and ZYPPER_EXIT_INF_RESTART_NEEDED: the package is installed. */
    private static final String ZYPPER_SUCCESS_CODES = "102|103";
    private static final String PACKAGE_NAME = "astrolabe-studio-update";
    private static final String DIRECTORY_PERMISSIONS = "rwxr-xr-x";
    private static final String FILE_PERMISSIONS = "rw-r--r--";
    private static final String POSIX = "posix";
    private static final String SHA256SUM = "sha256sum";

    /**
     * The one privileged command (a constant). Arguments: user's package,
     * expected SHA-256, package manager, sha256sum, then the manager's install words. The package is copied into a root-owned folder
     * (the user cannot swap files in it), hashed there and installed from that copy only; exit 3 means the hash differs.
     */
    static final String PRIVILEGED_INSTALL = """
            PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
            TMPDIR=/tmp; export TMPDIR
            PKG=$1; SUM=$2; MGR=$3; SHA=$4
            shift 4
            DIR=$(mktemp -d) || exit 1
            cleanup() { rm -rf "$DIR"; }
            trap cleanup EXIT
            chmod 755 "$DIR" || exit 1
            COPY=$DIR/${PKG##*/}
            cp -- "$PKG" "$COPY" || exit 1
            ACTUAL=$("$SHA" "$COPY") || exit 1
            ACTUAL=${ACTUAL%% *}
            [ "$ACTUAL" = "$SUM" ] || exit 3
            chmod 644 "$COPY" || exit 1
            "$MGR" "$@" "$COPY"
            """;

    /**
     * Runs as the user inside the terminal and keeps the window open after a failure. Arguments: status file, then the sudo command. It is
     * spliced into a single-quoted shell word, so it must never contain a single quote (a test enforces that).
     */
    static final String TERMINAL_WRAPPER = """
            STATUS=$1
            shift
            "$@"
            rc=$?
            printf "%s\\n" "$rc" > "$STATUS"
            if [ "$rc" -ne 0 ]; then echo "Update failed (exit $rc). Press Enter to close."; read -r _; fi
            exit "$rc"
            """;

    private static final String START = """
            case "$1" in /*) ;; *) STEP=check; return 1 ;; esac
            PRIV='""" + PRIVILEGED_INSTALL + """
            '
            WRAP='""" + TERMINAL_WRAPPER + """
            '
            """;

    private static final String PKEXEC_RUN = """
            AUTH_STEP=1
            step install "$2" /bin/sh -c "$PRIV" sh "$1" "$4" "$3" "$5" @INSTALL@
            rc=$?
            """;

    private static final String TERMINAL_RUN = """
            AUTH_STEP=0
            rm -f "$UPDATES/terminal-status"
            step install "$6" @FLAGS@ /bin/sh -c "$WRAP" sh "$UPDATES/terminal-status" "$7" /bin/sh -c "$PRIV" sh "$1" "$4" "$3" "$5" @INSTALL@
            trc=$?
            if [ -f "$UPDATES/terminal-status" ]; then read -r rc < "$UPDATES/terminal-status"; else rc=$trc; [ "$rc" -ne 0 ] || rc=1; fi
            case $rc in ''|*[!0-9]*) rc=1 ;; esac
            """;

    private static final String FINISH = """
            case "$(dirname "$1")" in */@@PREFIX@@*) rm -rf "$(dirname "$1")" ;; esac
            return $rc
            """;

    private final InstallKind kind;
    private final String extension;
    private final List<Manager> managers;
    private final HostEnvironment host;
    private final Path updatesDir;
    private final Path tempRoot;
    private final UpdateProperties properties;
    private final Map<Path, String> hashes = new ConcurrentHashMap<>();

    LinuxPackageUpdater(InstallKind kind, String extension, List<Manager> managers, HostEnvironment host, AppProperties app, Path tempRoot,
            UpdateProperties properties) {
        this.kind = kind;
        this.extension = extension;
        this.managers = List.copyOf(managers);
        this.host = host;
        this.updatesDir = app.dataDir().resolve(UPDATES_DIRECTORY);
        this.tempRoot = tempRoot;
        this.properties = properties;
    }

    @Component
    static class Deb extends LinuxPackageUpdater {
        @Autowired
        Deb(HostEnvironment host, AppProperties app, UpdateProperties properties) {
            super(InstallKind.DEB, ".deb", List.of(new Manager("apt-get", "install -y")), host, app, Path.of(System.getProperty("java.io.tmpdir")),
                    properties);
        }
    }

    @Component
    static class Rpm extends LinuxPackageUpdater {
        @Autowired
        Rpm(HostEnvironment host, AppProperties app, UpdateProperties properties) {
            super(InstallKind.RPM, ".rpm", List.of(new Manager("dnf", "install -y"),
                    new Manager("zypper", "--non-interactive install --allow-unsigned-rpm", ZYPPER_SUCCESS_CODES)), host, app, Path.of(System.getProperty("java.io.tmpdir")),
                    properties);
        }
    }

    @Override
    public InstallKind kind() {
        return kind;
    }

    @Override
    public boolean supported(Installation installation) {
        return installation.kind() == kind && plan(Path.of("/"), "").isPresent();
    }

    @Override
    public boolean needsAuthentication() {
        return true;
    }

    @Override
    public Path prepare(Path verifiedDownload, String verifiedSha256, Installation installation) throws IOException {
        var directory = tempRoot.resolve(PosixUpdateScript.STAGING_PREFIX + UUID.randomUUID());
        try {
            Files.createDirectory(directory);
            var copy = directory.resolve(PACKAGE_NAME + extension);
            Files.copy(verifiedDownload, copy, StandardCopyOption.REPLACE_EXISTING);
            if (directory.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(DIRECTORY_PERMISSIONS));
                Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString(FILE_PERMISSIONS));
            }
            // the copy (not the source, which could have been swapped since the verification) must match the verified hash
            UpdateFiles.requireSha256(copy, verifiedSha256, "The copied package");
            // the root step re-checks this hash on its own root-owned copy; prepare and launch run in one JVM, so memory is enough
            hashes.put(copy.toAbsolutePath(), verifiedSha256.toLowerCase(Locale.ROOT));
            return copy;
        } catch (IOException | RuntimeException e) {
            UpdateFiles.deleteQuietly(directory);
            throw e;
        }
    }

    /** @throws IllegalStateException when no package manager or authorisation route exists or the script cannot be started */
    @Override
    public void launchAfterExit(Path prepared, Installation installation, String targetVersion) {
        var hash = hashes.get(prepared.toAbsolutePath());
        if (hash == null) {
            throw new IllegalStateException("The package was not prepared by this process");
        }
        var plan = plan(prepared.toAbsolutePath(), hash)
                .orElseThrow(() -> new IllegalStateException("No package manager, sha256sum or authorisation tool found"));
        PosixUpdateScript.launch(updatesDir, ProcessHandle.current().pid(), installation.appExecutable(), plan.fragment(), plan.arguments(), List.of(),
                properties, PosixUpdateScript.Notices.linux(targetVersion, DESKTOP_ID, true));
        hashes.remove(prepared.toAbsolutePath());
    }

    @Override
    public void discard(Path prepared) {
        hashes.remove(prepared.toAbsolutePath());
    }

    /** pkexec first (falling back to a terminal with sudo when pkexec exits 127), else a terminal; the first manager that exists wins. */
    Optional<Plan> plan(Path pkg, String sha256Hex) {
        var manager = managers.stream().filter(candidate -> trusted(candidate.binary()).isPresent()).findFirst();
        var sha256sum = trusted(SHA256SUM);
        if (manager.isEmpty() || sha256sum.isEmpty()) {
            return Optional.empty();
        }
        var managerPath = trusted(manager.get().binary()).orElseThrow();
        var install = manager.get().installArgs();
        var pkexec = trusted(PKEXEC);
        var sudo = trusted(SUDO);
        Optional<Found> terminal = Optional.empty();
        if (sudo.isPresent()) {
            terminal = TERMINALS.stream().flatMap(t -> trusted(t.binary()).map(path -> new Found(path, t)).stream()).findFirst();
        }
        if (pkexec.isEmpty() && terminal.isEmpty()) {
            return Optional.empty();
        }
        var body = new StringBuilder(START);
        if (pkexec.isPresent()) {
            body.append(PKEXEC_RUN.replace("@INSTALL@", install));
            body.append("if [ \"$rc\" -eq 127 ]; then\n");
            body.append(terminal.isPresent() ? terminalRun(terminal.get(), install) : "STEP=pkexec\n").append("fi\n");
        } else {
            body.append(terminalRun(terminal.get(), install));
        }
        if (!manager.get().successCodes().isEmpty()) {
            body.append("case $rc in ").append(manager.get().successCodes()).append(") rc=0 ;; esac\n");
        }
        body.append(FINISH);
        return Optional.of(new Plan(body.toString(), List.of(pkg.toString(), pkexec.orElse(""), managerPath, sha256Hex, sha256sum.get(),
                terminal.map(Found::path).orElse(""), sudo.orElse(""))));
    }

    private static String terminalRun(Found terminal, String install) {
        return TERMINAL_RUN.replace("@FLAGS@", terminal.terminal().flags()).replace("@INSTALL@", install);
    }

    private Optional<String> trusted(String binary) {
        return TRUSTED_DIRECTORIES.stream().map(directory -> Path.of(directory).resolve(binary)).filter(host::isFile).map(Path::toString).findFirst();
    }
}
