package com.github.krpkbhdn.astrolabe.author.install;

import com.github.krpkbhdn.astrolabe.author.AuthorItem;
import com.github.krpkbhdn.astrolabe.extension.ExtensionException;
import com.github.krpkbhdn.astrolabe.extension.SettingsFragmentMerger;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.util.List;
import org.springframework.stereotype.Service;

/** Merges the fragment of a SETTINGS item into the user's Claude {@code settings.json}; a differing existing value is left and reported. */
@Service
public class SettingsInstaller {

    private static final String SETTINGS_FILE = "settings.json";

    private final HostEnvironment host;

    public SettingsInstaller(HostEnvironment host) {
        this.host = host;
    }

    public InstallOutcome install(AuthorItem item) {
        var label = "settings " + item.title();
        try {
            var result = SettingsFragmentMerger.mergeFile(host.claudeConfigDirectory().resolve(SETTINGS_FILE), item.fragment());
            if (!result.conflicts().isEmpty()) {
                var differs = label + " (differs from yours: " + String.join(", ", result.conflicts()) + ")";
                return new InstallOutcome(List.of(), List.of(), List.of(differs), List.of(), List.of());
            }
            return result.changed()
                    ? new InstallOutcome(List.of(label), List.of(), List.of(), List.of(), List.of())
                    : new InstallOutcome(List.of(), List.of(label), List.of(), List.of(), List.of());
        } catch (ExtensionException e) {
            return new InstallOutcome(List.of(), List.of(), List.of(), List.of(label + ": " + e.getMessage()), List.of());
        }
    }
}
