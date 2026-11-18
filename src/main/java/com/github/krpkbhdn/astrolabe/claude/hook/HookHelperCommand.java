package com.github.krpkbhdn.astrolabe.claude.hook;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How Claude runs {@link HookPoster} (the app itself; the token is read from a file named by {@code --token-file}, never on the command line): an exec form for SessionStart, a shell string for statusLine.
 * On Windows the string uses 8.3 short paths and forward slashes, so it needs no quoting in Git Bash or PowerShell; a launcher path that still needs quotes is used only with Git Bash.
 */
@Component
public class HookHelperCommand {

    /** A launcher with its fixed arguments; {@code args} are one argument each, as Claude's exec form takes them. */
    public record Exec(String command, List<String> args) {
    }

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=+%@~-]+");
    private static final Pattern UNQUOTABLE_ON_WINDOWS = Pattern.compile("[$`\"]");
    private static final Pattern JAVA_BINARY = Pattern.compile("javaw?(\\.exe)?", Pattern.CASE_INSENSITIVE);
    private static final List<String> DEV_JVM_OPTIONS = List.of("-Xshare:auto", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC");
    private static final String NO_LAUNCHER_REASON = "The path of the running application could not be determined, so usage and context meters are off.";
    private static final String NO_SHELL_FORM_REASON = "The application path has no usable short DOS name (spaces or special characters) and Git Bash was not found, "
            + "so usage and context meters are off.";
    private static final String GIT_BASH_NOTE = "The application path has spaces and no short DOS name, so the usage and context meters need Git Bash.";

    private static final String TOKEN_FILE_FLAG = "--token-file";

    private final UnaryOperator<String> shortPath;
    private final String launcher;
    private final String helperLocation;
    private final boolean windows;
    private final String shellLauncher;
    private final String shellLocation;
    private final String unavailableReason;
    private final String shellFormNote;

    @Autowired
    public HookHelperCommand() {
        this(ProcessHandle.current().info().command(), codeSource(), isWindows(), WindowsShortPath::of, isWindows() && GitBashDetector.system().present());
    }

    HookHelperCommand(Optional<String> launcher, String helperLocation, boolean windows, UnaryOperator<String> shortPath, boolean gitBash) {
        this.shortPath = shortPath;
        this.launcher = launcher.orElse(null);
        this.helperLocation = helperLocation;
        this.windows = windows;
        this.shellLauncher = this.launcher == null ? "" : windows ? shortPath.apply(this.launcher) : this.launcher;
        this.shellLocation = windows ? shortPath.apply(helperLocation) : helperLocation;
        var launcherNeedsQuotes = windows && !isPlain(shellLauncher);
        if (this.launcher == null) {
            this.unavailableReason = NO_LAUNCHER_REASON;
        } else if (windows && (hasUnquotable(shellLauncher) || isJava(shellLauncher) && hasUnquotable(shellLocation) || launcherNeedsQuotes && !gitBash)) {
            this.unavailableReason = NO_SHELL_FORM_REASON;
        } else {
            this.unavailableReason = null;
        }
        this.shellFormNote = unavailableReason == null && launcherNeedsQuotes ? GIT_BASH_NOTE : null;
    }

    public boolean windows() {
        return windows;
    }

    /** The exec form that posts its stdin to {@code url}; empty when the app's own launcher is unknown. */
    public Optional<Exec> execForm(String url) {
        return execForm(url, null);
    }

    /** As {@link #execForm(String)}; the helper reads the token from {@code tokenFile} (null: from the environment only). */
    public Optional<Exec> execForm(String url, Path tokenFile) {
        return launcher == null ? Optional.empty()
                : Optional.of(new Exec(launcher, arguments(launcher, helperLocation, url, tokenFile == null ? null : tokenFile.toString())));
    }

    public Optional<String> shellCommand(String url) {
        return shellCommand(url, null);
    }

    /** One shell string that posts its stdin to {@code url}; empty when there is no launcher or no shell form that works on this machine. */
    public Optional<String> shellCommand(String url, Path tokenFile) {
        var file = tokenFile == null ? null : windows ? shortPath.apply(tokenFile.toString()) : tokenFile.toString();
        if (unavailableReason != null || windows && file != null && hasUnquotable(file)) {
            return Optional.empty();
        }
        var parts = new ArrayList<String>();
        parts.add(quoted(shellLauncher));
        arguments(shellLauncher, shellLocation, url, file).forEach(argument -> parts.add(quoted(argument)));
        return Optional.of(String.join(" ", parts));
    }

    /** Why the status line command is not written (no launcher, or no usable Windows path form), for the UI. */
    public Optional<String> unavailableReason() {
        return Optional.ofNullable(unavailableReason);
    }

    /** Set when the launcher path had to be quoted, so the status line command works under Git Bash only, for the UI. */
    public Optional<String> shellFormNote() {
        return Optional.ofNullable(shellFormNote);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    private static boolean hasUnquotable(String path) {
        return UNQUOTABLE_ON_WINDOWS.matcher(path).find();
    }

    private static boolean isPlain(String path) {
        return PLAIN.matcher(path.replace('\\', '/')).matches();
    }

    private static boolean isJava(String path) {
        return JAVA_BINARY.matcher(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)).matches();
    }

    /** The token file follows the url directly: HookPoster reads its arguments by position. */
    private static List<String> arguments(String path, String helperLocation, String url, String tokenFile) {
        var line = new ArrayList<String>();
        if (isJava(path)) {
            line.addAll(DEV_JVM_OPTIONS);
            line.addAll(List.of("-cp", helperLocation, HookPoster.class.getName()));
        }
        line.addAll(List.of(HookPoster.FLAG, url));
        if (tokenFile != null) {
            line.addAll(List.of(TOKEN_FILE_FLAG, tokenFile));
        }
        return line;
    }

    private String quoted(String arg) {
        var text = windows ? arg.replace('\\', '/') : arg;
        if (PLAIN.matcher(text).matches()) {
            return text;
        }
        return windows ? "\"" + text + "\"" : "'" + text.replace("'", "'\\''") + "'";
    }

    private static String codeSource() {
        try {
            return Path.of(HookPoster.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the hook helper", e);
        }
    }
}
