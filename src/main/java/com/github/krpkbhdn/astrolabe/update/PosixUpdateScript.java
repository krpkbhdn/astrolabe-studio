package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.overlay.OwnerOnlyFiles;
import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Detached sh script shared by the macOS and Linux updaters: waits for this process to exit, runs the platform steps, writes
 * {@code <updates>/result} ({@code OK}, {@code AUTH_CANCELLED} or {@code FAILED <step>}), relaunches the app even on failure and removes
 * its own folder. Paths are positional arguments only, never script text; the log holds timestamps, step names and exit codes.
 *
 * <p>The platform part is a script fragment (trusted constant, no paths in it) that runs as the body of a shell function and sees
 * the {@code extraArgs} as {@code "$1"}, {@code "$2"}, ... Run each command through {@code step <name> <command> [args]}, which logs
 * it and records the name for the result; the fragment ends with the exit status of its last command. {@link #AUTH_CANCELLED_EXIT}
 * (126, and only that, and only while the fragment has set {@code AUTH_STEP=1} for the authorisation step, e.g. a dismissed pkexec dialog)
 * means the user dismissed the authorisation prompt; any other non-zero status is a failure.
 * A process that outlives the pid wait timeout gets TERM, then KILL after the grace period, and the update goes on.
 *
 * <p>The whole body is one {@code { ... }; exit} compound command, which the shell parses completely before it runs anything (and the
 * {@code exit} stops it from ever reading further), so rewriting the file while the script waits for the pid, or while the user answers
 * the authorisation dialog, changes nothing: the privileged text is a constant that only ever travels as an {@code sh -c} argument. The
 * file is {@code 0500} in a {@code 0700} folder, which is hygiene only. Residual boundary: this is protection against a swapped
 * <em>file</em>, not against code running as the same user, which can still signal or ptrace the waiting shell, or change what it passes on
 * (package path, hash) before the root step starts; such code can already do everything the user can.
 */
final class PosixUpdateScript {

    static final int AUTH_CANCELLED_EXIT = 126;
    static final String RESULT_FILE = "result";
    /** Prefix of the temp folders the updaters stage in; scripts use {@code @@PREFIX@@} for it. */
    static final String STAGING_PREFIX = "astrolabe-update-";

    private static final String SCRIPT_NAME = "update.sh";
    private static final String FRAGMENT_MARKER = "@@PLATFORM_STEPS@@";
    private static final String PREFIX_MARKER = "@@PREFIX@@";
    private static final String AUTH_EXIT_MARKER = "@@AUTH_EXIT@@";
    private static final String SCRIPT_PERMISSIONS = "r-x------";
    private static final String POSIX = "posix";

    /** Arguments: updates folder, pid, app executable, pid wait seconds, kill grace seconds, number of fragment arguments, those, then folders to delete after success. */
    private static final String TEMPLATE = """
            #!/bin/sh
            {
            UPDATES=$1
            PID=$2
            APP=$3
            WAIT=$4
            GRACE=$5
            NEXTRA=$6
            shift 6
            SELF=$(dirname "$0")
            STEP=update
            AUTH_STEP=0
            log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$1" >> "$UPDATES/update.log"; }
            step() {
              STEP=$1
              shift
              log "step $STEP"
              "$@"
              rc=$?
              log "step $STEP exit $rc"
              return $rc
            }
            platform_update() {
            @@PLATFORM_STEPS@@
            }
            waited=0
            while kill -0 "$PID" 2>/dev/null; do
              if [ "$waited" -ge "$WAIT" ]; then
                log "wait timeout, TERM"
                kill -TERM "$PID" 2>/dev/null
                graced=0
                while kill -0 "$PID" 2>/dev/null && [ "$graced" -lt "$GRACE" ]; do sleep 1; graced=$((graced + 1)); done
                if kill -0 "$PID" 2>/dev/null; then log "grace over, KILL"; kill -KILL "$PID" 2>/dev/null; sleep 1; fi
                break
              fi
              sleep 1
              waited=$((waited + 1))
            done
            platform_update "$@"
            rc=$?
            case $rc in
              0) RESULT=OK ;;
              @@AUTH_EXIT@@) if [ "$AUTH_STEP" = 1 ]; then RESULT=AUTH_CANCELLED; else RESULT="FAILED $STEP"; fi ;;
              *) RESULT="FAILED $STEP" ;;
            esac
            printf '%s\n' "$RESULT" > "$UPDATES/result"
            log "result $RESULT"
            if [ "$rc" -eq 0 ]; then
              shift "$NEXTRA"
              for leftover in "$@"; do rm -rf -- "$leftover"; done
            fi
            log "relaunch"
            "$APP" >/dev/null 2>&1 </dev/null &
            case "$SELF" in */@@PREFIX@@*) rm -rf "$SELF" ;; esac
            }; exit
            """;

    private PosixUpdateScript() {
    }

    /**
     * @param updatesDir {@code <dataDir>/updates}, where the log and result are written
     * @param extraArgs the fragment's positional arguments
     * @param cleanup folders deleted after a successful update only (a failed one keeps them for the next try)
     * @throws IllegalStateException when the script cannot be written or started
     */
    static void launch(Path updatesDir, long pid, Path appExecutable, String fragment, List<String> extraArgs, List<Path> cleanup,
            UpdateProperties properties) {
        Path directory = null;
        try {
            Files.createDirectories(updatesDir);
            directory = Path.of(System.getProperty("java.io.tmpdir"), STAGING_PREFIX + UUID.randomUUID());
            OwnerOnlyFiles.createDirectory(directory);
            var script = directory.resolve(SCRIPT_NAME);
            OwnerOnlyFiles.write(script, script(fragment));
            if (script.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString(SCRIPT_PERMISSIONS));
            }
            CommandRunner.startDetached(command(script, updatesDir, pid, appExecutable, properties.pidWaitTimeout().toSeconds(),
                    properties.pidKillGrace().toSeconds(), extraArgs, cleanup));
        } catch (IOException e) {
            UpdateFiles.deleteQuietly(directory);
            throw new IllegalStateException("Could not start the installer: " + e.getMessage(), e);
        }
    }

    static String script(String fragment) {
        return TEMPLATE.replace(FRAGMENT_MARKER, fragment).replace(PREFIX_MARKER, STAGING_PREFIX).replace(AUTH_EXIT_MARKER,
                Integer.toString(AUTH_CANCELLED_EXIT));
    }

    /** {@code setsid} detaches the script from the app's session when it exists; the paths stay positional arguments. */
    static List<String> command(Path script, Path updatesDir, long pid, Path appExecutable, long waitSeconds, long graceSeconds, List<String> extraArgs,
            List<Path> cleanup) {
        var command = new ArrayList<>(List.of("/bin/sh", "-c", "if command -v setsid >/dev/null 2>&1; then exec setsid \"$@\"; else exec \"$@\"; fi", "sh",
                "/bin/sh", script.toAbsolutePath().toString(), updatesDir.toAbsolutePath().toString(), Long.toString(pid), appExecutable.toAbsolutePath().toString(),
                Long.toString(waitSeconds), Long.toString(graceSeconds), Integer.toString(extraArgs.size())));
        command.addAll(extraArgs);
        cleanup.forEach(path -> command.add(path.toAbsolutePath().toString()));
        return command;
    }
}
