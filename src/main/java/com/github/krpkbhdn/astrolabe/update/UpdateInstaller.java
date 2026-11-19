package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Windows only: a detached script waits for this process to exit, runs the per-user MSI upgrade and starts the app again.
 * The paths are script arguments because cmd reads the script in the OEM code page, which mangles non-ASCII user names.
 */
@Component
class UpdateInstaller {

    private static final String APP_PATH_PROPERTY = "jpackage.app-path";

    private final HostEnvironment host;

    UpdateInstaller(HostEnvironment host) {
        this.host = host;
    }

    boolean supported() {
        return host.os() == HostEnvironment.OperatingSystem.WINDOWS && System.getProperty(APP_PATH_PROPERTY) != null;
    }

    /** @throws IllegalStateException when the installer script cannot be started */
    void launchAfterExit(Path msi) {
        var appExecutable = System.getProperty(APP_PATH_PROPERTY);
        if (appExecutable == null) {
            throw new IllegalStateException("The app was not started from an installed package");
        }
        Path script = null;
        try {
            script = Files.createTempFile("astrolabe-update", ".cmd");
            Files.writeString(script, script(ProcessHandle.current().pid()));
            CommandRunner.startDetached(command(script, msi, appExecutable));
        } catch (IOException e) {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                    // the temp directory is cleaned by the OS eventually
                }
            }
            throw new IllegalStateException("Could not start the installer: " + e.getMessage(), e);
        }
    }

    /**
     * The script runs directly, the JDK quotes the arguments. Not through {@code cmd /c start}: start re-runs a .cmd as
     * {@code cmd /K "script" "arg"}, whose outer-quote rule breaks a path with spaces ("... is not recognized") and leaves an idle console.
     */
    static List<String> command(Path script, Path msi, String appExecutable) {
        return List.of(script.toString(), msi.toString(), Path.of(appExecutable).toAbsolutePath().toString());
    }

    static String script(long pid) {
        return """
                @echo off
                :wait
                tasklist /FI "PID eq %d" 2>nul | find "%d" >nul
                if not errorlevel 1 (
                  timeout /t 1 /nobreak >nul
                  goto wait
                )
                set LOG="%%~dp1update.log"
                echo %%date%% %%time%% installing "%%~1" >> %%LOG%%
                msiexec /i "%%~1" /passive /norestart /l*v "%%~dp1update-msi.log"
                echo %%date%% %%time%% msiexec exit code %%errorlevel%%, starting "%%~2" >> %%LOG%%
                start "" "%%~2" >> %%LOG%% 2>&1
                del "%%~f0"
                """.formatted(pid, pid);
    }
}
