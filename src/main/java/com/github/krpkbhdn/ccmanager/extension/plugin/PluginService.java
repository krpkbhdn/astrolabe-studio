package com.github.krpkbhdn.ccmanager.extension.plugin;

import com.github.krpkbhdn.ccmanager.extension.ClaudeCli;
import com.github.krpkbhdn.ccmanager.extension.ExtensionException;
import com.github.krpkbhdn.ccmanager.extension.ExtensionProperties;
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope;
import com.github.krpkbhdn.ccmanager.extension.Json;
import com.github.krpkbhdn.ccmanager.workspace.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Plugins and marketplaces through {@code claude plugin}. Project and local scope commands run in the project directory.
 * All methods block and throw {@link ExtensionException} with the CLI's message.
 */
@Service
public class PluginService {

    private static final Pattern PLUGIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.@-]*");

    private static final String ALREADY_IN_GOAL_STATE = "already_in_goal_state";

    private static final String JSON_FLAG = "--json";
    private static final String UNKNOWN_JSON_OPTION = "unknown option '--json'";

    private final ClaudeCli cli;
    private final ExtensionProperties properties;
    private volatile List<AvailablePlugin> availableCache;

    public PluginService(ClaudeCli cli, ExtensionProperties properties) {
        this.cli = cli;
        this.properties = properties;
    }

    /** Every installation in every scope for views without a project; project entries then read as disabled, see {@link #installed(Path)}. */
    public List<InstalledPlugin> installed() {
        return installed(null);
    }

    /**
     * Every installation in every scope; project and local entries carry their projectPath. The CLI computes {@code enabled} of
     * project entries relative to its working directory, so a project's view must pass that project's folder; a missing folder
     * falls back to the global read (project-scoped {@code enabled} is then unreliable, but the screens keep working).
     */
    public List<InstalledPlugin> installed(Path projectDirectory) {
        var directory = projectDirectory != null && Files.isDirectory(projectDirectory) ? projectDirectory : null;
        return PluginParser.installed(cli.executeChecked(directory, properties.queryTimeout(), List.of("plugin", "list", "--json")));
    }

    /** The marketplace catalogue (thousands of entries), fetched once; {@link #refreshAvailable()} reloads it. */
    public List<AvailablePlugin> available() {
        var cached = availableCache;
        return cached != null ? cached : refreshAvailable();
    }

    public synchronized List<AvailablePlugin> refreshAvailable() {
        var json = cli.executeChecked(null, properties.networkTimeout(), List.of("plugin", "list", "--json", "--available"));
        availableCache = PluginParser.available(json);
        return availableCache;
    }

    public List<Marketplace> marketplaces() {
        return PluginParser.marketplaces(
                cli.executeChecked(null, properties.queryTimeout(), List.of("plugin", "marketplace", "list", "--json")));
    }

    /** Project and local plugins are only known inside their project, so the CLI runs in projectDirectory (null for user scope). */
    public PluginDetails details(String pluginId, Path projectDirectory) {
        return PluginParser.details(cli.executeChecked(projectDirectory, properties.queryTimeout(), List.of("plugin", "details", requireValidId(pluginId))));
    }

    /** First step of an install; never accepts a marketplace command on its own, see {@link PluginInstallResult}. */
    public PluginInstallResult install(String pluginId, ExtensionScope scope, Path projectDirectory) {
        return withCommandCheck("install", pluginId, scope, projectDirectory, List.of());
    }

    /** Second step, only after a person confirmed {@code command}: accepts exactly the command with this sha256. */
    public PluginInstallResult installAccepting(String pluginId, ExtensionScope scope, Path projectDirectory, ShownCommand command) {
        return withCommandCheck("install", pluginId, scope, projectDirectory, acceptance(command));
    }

    /** Like {@link #install}: an update can also be held back until a marketplace command is confirmed. */
    public PluginInstallResult update(String pluginId, ExtensionScope scope, Path projectDirectory) {
        return withCommandCheck("update", pluginId, scope, projectDirectory, List.of());
    }

    public PluginInstallResult updateAccepting(String pluginId, ExtensionScope scope, Path projectDirectory, ShownCommand command) {
        return withCommandCheck("update", pluginId, scope, projectDirectory, acceptance(command));
    }

    private static List<String> acceptance(ShownCommand command) {
        return List.of("--accept-command", command.sha256());
    }

    private PluginInstallResult withCommandCheck(
            String action, String pluginId, ExtensionScope scope, Path projectDirectory, List<String> extra) {
        var arguments = scoped(action, pluginId, scope);
        arguments.addAll(extra);
        var result = executeWithJsonFallback(scope.workingDirectory(projectDirectory), arguments);
        var parsed = PluginParser.installResult(result.exitCode(), result.stdout(), result.stderr());
        if (!parsed.installed() && !parsed.needsConfirmation()) {
            throw ClaudeCli.failure(arguments, result);
        }
        return parsed;
    }

    public void uninstall(String pluginId, ExtensionScope scope, Path projectDirectory) {
        run(scoped("uninstall", pluginId, scope), scope, projectDirectory);
    }

    public void setEnabled(String pluginId, ExtensionScope scope, Path projectDirectory, boolean enabled) {
        var arguments = scoped(enabled ? "enable" : "disable", pluginId, scope);
        var result = executeWithJsonFallback(scope.workingDirectory(projectDirectory), arguments);
        if (!result.succeeded() && !alreadyInGoalState(pluginId, result)) {
            throw ClaudeCli.failure(arguments, result);
        }
    }

    /** The CLI's refusal to enable or disable what already is: the JSON failureCode, else the exact text of older versions for this plugin. */
    private static boolean alreadyInGoalState(String pluginId, CommandRunner.Result result) {
        try {
            if (ALREADY_IN_GOAL_STATE.equals(Json.text(Json.parse(result.stdout()), "failureCode"))) {
                return true;
            }
        } catch (ExtensionException notJson) {
            // older versions answer in plain text
        }
        var text = Pattern.compile("Plugin \"" + Pattern.quote(pluginId) + "\" is already (?:enabled|disabled)");
        return !result.succeeded() && text.matcher(result.stdout() + result.stderr()).find();
    }

    public void addMarketplace(String source, ExtensionScope scope, Path projectDirectory) {
        run(List.of("plugin", "marketplace", "add", requireNotOption(source), "--scope", scope.cliValue(), "--json"), scope, projectDirectory);
        availableCache = null;
    }

    public void removeMarketplace(String name) {
        run(List.of("plugin", "marketplace", "remove", requireNotOption(name)), ExtensionScope.USER, null);
        availableCache = null;
    }

    /** Refreshes one marketplace, or all of them when name is null. */
    public void updateMarketplace(String name) {
        var arguments = new ArrayList<>(List.of("plugin", "marketplace", "update"));
        if (name != null) {
            arguments.add(requireNotOption(name));
        }
        run(arguments, ExtensionScope.USER, null);
        availableCache = null;
    }

    private void run(List<String> arguments, ExtensionScope scope, Path projectDirectory) {
        var result = executeWithJsonFallback(scope.workingDirectory(projectDirectory), arguments);
        if (!result.succeeded()) {
            throw ClaudeCli.failure(arguments, result);
        }
    }

    /** Older claude versions reject --json on mutating plugin commands; the command is then repeated once without it. */
    private CommandRunner.Result executeWithJsonFallback(Path directory, List<String> arguments) {
        var result = cli.execute(directory, properties.networkTimeout(), arguments);
        if (result.succeeded() || !result.stderr().contains(UNKNOWN_JSON_OPTION)) {
            return result;
        }
        var withoutJson = new ArrayList<>(arguments);
        withoutJson.remove(JSON_FLAG);
        return cli.execute(directory, properties.networkTimeout(), withoutJson);
    }

    private static List<String> scoped(String action, String pluginId, ExtensionScope scope) {
        return new ArrayList<>(List.of("plugin", action, requireValidId(pluginId), "--scope", scope.cliValue(), "--json"));
    }

    private static String requireNotOption(String argument) {
        if (argument.startsWith("-")) {
            throw new ExtensionException("Must not start with '-': " + argument);
        }
        return argument;
    }

    private static String requireValidId(String argument) {
        if (!PLUGIN_ID.matcher(argument).matches()) {
            throw new ExtensionException("Invalid plugin id (letters, digits, '_', '.', '@' and '-' only, starting with a letter or digit): " + argument);
        }
        return argument;
    }

}
