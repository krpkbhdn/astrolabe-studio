package com.github.krpkbhdn.ccmanager.toolchain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Rejects catalogs whose references are broken or whose install commands could escalate privileges or inject shell syntax. */
final class ToolCatalogValidator {

    private static final List<String> FORBIDDEN_COMMANDS = List.of("sudo", "su", "doas", "runas", "pkexec");
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of("-verb", "runas");
    private static final List<String> SHELL_METACHARACTERS =
            List.of("|", "&", ";", ">", "<", "`", "$(", "%", "^", "\"", "!", "\r", "\n");
    static final List<String> ALLOWED_INSTALLERS =
            List.of("winget", "npm", "brew", "go", "dotnet", "rustup", "pip", "pipx", "cargo");
    private static final List<String> FORBIDDEN_INTERPRETERS =
            List.of("sh", "bash", "zsh", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "-c", "-command");
    static final Map<String, String> ALLOWED_VERBS = Map.of("npm", "install", "go", "install", "dotnet", "tool",
            "rustup", "component", "winget", "install", "brew", "install", "pip", "install", "pipx", "install", "cargo", "install");
    private static final List<String> FORBIDDEN_OPTION_PREFIXES = List.of("--registry", "--prefix", "--source", "--index-url",
            "--extra-index-url", "--userconfig", "--globalconfig", "-c", "--config", "--exec");
    private static final Pattern BINARY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final List<String> INSTALL_OS_KEYS = List.of("windows", "linux", "macos");
    private static final int MAX_MANUAL_COMMAND = 300;
    private static final Pattern LSP_PLUGIN = Pattern.compile("[a-z0-9-]+@[a-z0-9-]+");

    static final List<String> DOWNLOAD_HOSTS = List.of("download.eclipse.org", "github.com", "download.jetbrains.com",
            "download-cdn.jetbrains.com");
    private static final String VERSION_PLACEHOLDER = "{version}";
    private static final String VERSION_MARKER = "VERSIONMARKER";

    private ToolCatalogValidator() {}

    static void validate(List<ToolDefinition> tools) {
        var problems = new ArrayList<String>();
        Map<String, ToolDefinition> byId = new HashMap<>();
        for (var tool : tools) {
            if (byId.putIfAbsent(tool.id(), tool) != null) {
                problems.add("duplicate id " + tool.id());
            }
        }
        for (var tool : tools) {
            checkReferences(tool, byId, problems);
            checkIdentity(tool, problems);
            tool.install().forEach((os, argv) -> checkInstall(tool.id(), os, argv, problems));
            tool.manualInstall().forEach((os, line) -> checkManualInstall(tool.id(), os, line, problems));
            tool.download().forEach((os, source) -> checkDownload(tool, os, source, problems));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid toolchain catalog: " + String.join("; ", problems));
        }
    }

    private static void checkReferences(ToolDefinition tool, Map<String, ToolDefinition> byId, List<String> problems) {
        if (!tool.prerequisite().isEmpty()) {
            var prerequisite = byId.get(tool.prerequisite());
            if (prerequisite == null || prerequisite.kind() != ToolKind.PREREQUISITE) {
                problems.add(tool.id() + ": unknown prerequisite " + tool.prerequisite());
            }
        }
        if (tool.kind() == ToolKind.ADD_ON) {
            var base = byId.get(tool.baseTool());
            if (base == null || base.kind() != ToolKind.LANGUAGE_SERVER) {
                problems.add(tool.id() + ": add-on needs a language server baseTool, got '" + tool.baseTool() + "'");
            }
        }
    }

    private static void checkIdentity(ToolDefinition tool, List<String> problems) {
        var binary = tool.binary();
        if (binary.isBlank() || binary.contains("/") || binary.contains("\\") || binary.contains("..")) {
            problems.add(tool.id() + ": binary must be a plain file name");
        }
        if (!tool.lspPlugin().isEmpty() && !LSP_PLUGIN.matcher(tool.lspPlugin()).matches()) {
            problems.add(tool.id() + ": lspPlugin must look like name@marketplace");
        }
        tool.install().keySet().stream().filter(os -> !INSTALL_OS_KEYS.contains(os))
                .forEach(os -> problems.add(tool.id() + ": unknown install key '" + os + "'"));
        if (!BINARY.matcher(binary).matches()) {
            problems.add(tool.id() + ": binary must match [A-Za-z0-9._-]+");
        }
        tool.versionArgs().stream().filter(ToolCatalogValidator::hasMetacharacter)
                .forEach(token -> problems.add(tool.id() + ": shell metacharacter in versionArgs '" + token + "'"));
        tool.versionArgs().forEach(token -> checkToken(tool.id() + " versionArgs", token, problems));
    }

    private static void checkToken(String where, String token, List<String> problems) {
        var lower = token.toLowerCase(Locale.ROOT);
        if (token.chars().anyMatch(c -> Character.isWhitespace(c) || c > 127)) {
            problems.add(where + ": whitespace or non-ASCII character in '" + token + "'");
        }
        if (lower.equals("--") || FORBIDDEN_OPTION_PREFIXES.stream().anyMatch(lower::startsWith)) {
            problems.add(where + ": forbidden option '" + token + "'");
        }
    }

    private static boolean hasMetacharacter(String token) {
        return SHELL_METACHARACTERS.stream().anyMatch(token::contains);
    }

    /** Display-only text the user runs themselves, so the shell, sudo and allowlist rules of install argv do not apply. */
    private static void checkManualInstall(String id, String os, String line, List<String> problems) {
        var where = id + " manualInstall " + os;
        if (!INSTALL_OS_KEYS.contains(os)) {
            problems.add(id + ": unknown manualInstall key '" + os + "'");
        }
        if (line.isBlank() || line.contains("\r") || line.contains("\n") || line.length() > MAX_MANUAL_COMMAND) {
            problems.add(where + ": must be one non-blank line of at most " + MAX_MANUAL_COMMAND + " characters");
        }
    }

    private static void checkDownload(ToolDefinition tool, String os, DownloadSource source, List<String> problems) {
        var where = tool.id() + " download " + os;
        if (!INSTALL_OS_KEYS.contains(os)) {
            problems.add(tool.id() + ": unknown download key '" + os + "'");
        }
        if (tool.kind() != ToolKind.LANGUAGE_SERVER) {
            problems.add(where + ": only language servers can be downloaded");
        }
        if (tool.hasInstallCommand(os)) {
            problems.add(where + ": has an install command for the same OS");
        }
        checkUrl(where + " latest.url", source.latest().url(), false, problems);
        checkUrl(where + " archiveUrl", source.archiveUrl(), true, problems);
        if (!stripVersion(source.archiveUrl()).endsWith(source.archive().suffix())) {
            problems.add(where + ": archiveUrl must end with " + source.archive().suffix());
        }
        checkPattern(where, source.latest().pattern(), problems);
        checkLauncher(where, source.launcher(), problems);
    }

    private static String stripVersion(String url) {
        return url.replace(VERSION_PLACEHOLDER, "1");
    }

    private static void checkUrl(String where, String url, boolean versionAllowed, List<String> problems) {
        if (!versionAllowed && url.contains(VERSION_PLACEHOLDER)) {
            problems.add(where + ": {version} is not allowed here");
            return;
        }
        URI uri;
        try {
            uri = new URI(url.replace(VERSION_PLACEHOLDER, VERSION_MARKER));
        } catch (URISyntaxException e) {
            problems.add(where + ": not a valid URL '" + url + "'");
            return;
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1) {
            problems.add(where + ": must be a plain https URL without credentials or port");
            return;
        }
        if (!DOWNLOAD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            problems.add(where + ": host '" + uri.getHost() + "' is not allowed");
        }
        var outsidePath = uri.getHost() + uri.getRawQuery() + uri.getRawFragment();
        if (outsidePath.contains(VERSION_MARKER) || url.replace(VERSION_PLACEHOLDER, "").matches(".*[{}].*")) {
            problems.add(where + ": {version} may only appear in the path");
        }
    }

    private static void checkPattern(String where, String pattern, List<String> problems) {
        try {
            if (Pattern.compile(pattern).matcher("").groupCount() != 1) {
                problems.add(where + ": latest.pattern needs exactly one group");
            }
        } catch (PatternSyntaxException e) {
            problems.add(where + ": latest.pattern does not compile");
        }
    }

    private static void checkLauncher(String where, LauncherSpec launcher, List<String> problems) {
        var path = launcher.path();
        if (launcher.kind() == LauncherSpec.Kind.JDTLS) {
            if (!path.isEmpty()) {
                problems.add(where + ": a JDTLS launcher takes no path");
            }
            if (!launcher.args().isEmpty()) {
                problems.add(where + ": a JDTLS launcher takes no args");
            }
            return;
        }
        launcher.args().stream()
                .filter(arg -> arg.isEmpty() || hasMetacharacter(arg) || arg.chars().anyMatch(c -> Character.isWhitespace(c) || c > 127))
                .forEach(arg -> problems.add(where + ": launcher arg '" + arg + "' must be a plain token"));
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                || hasMetacharacter(path) || path.chars().anyMatch(c -> Character.isWhitespace(c) || c > 127)) {
            problems.add(where + ": launcher path must be relative with / separators and no .. or shell characters");
        }
    }

    private static void checkInstall(String id, String os, List<String> argv, List<String> problems) {
        if (argv.isEmpty()) {
            return;
        }
        var where = id + " install " + os;
        var first = argv.getFirst();
        if (first.contains("/") || first.contains("\\")) {
            problems.add(where + ": first token must be a plain executable name");
        }
        if (!ALLOWED_INSTALLERS.contains(first)) {
            problems.add(where + ": installer '" + first + "' is not allowed");
        }
        var verb = ALLOWED_VERBS.get(first);
        if (verb != null && !(argv.size() > 1 && verb.equals(argv.get(1)))) {
            problems.add(where + ": '" + first + "' may only run '" + verb + "'");
        }
        for (var token : argv) {
            checkToken(where, token, problems);
            var lower = token.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_COMMANDS.contains(lower) || FORBIDDEN_FRAGMENTS.stream().anyMatch(lower::contains)) {
                problems.add(where + ": privilege escalation token '" + token + "'");
            }
            if (FORBIDDEN_INTERPRETERS.contains(lower)) {
                problems.add(where + ": shell or interpreter token '" + token + "'");
            }
            if (hasMetacharacter(token)) {
                problems.add(where + ": shell metacharacter in '" + token + "'");
            }
        }
    }
}
