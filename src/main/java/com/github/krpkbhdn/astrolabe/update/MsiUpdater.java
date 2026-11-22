package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
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
class MsiUpdater implements PlatformUpdater {

    /** Runs {@code cmd /d /c ""script" "msi" "exe""} hidden without waiting, then deletes itself; the doubled quotes survive cmd's outer-quote rule. */
    private static final String LAUNCHER = """
            Set a = WScript.Arguments
            q = Chr(34)
            CreateObject("WScript.Shell").Run "cmd /d /c " & q & q & a(0) & q & " " & q & a(1) & q & " " & q & a(2) & q & q, 0, False
            CreateObject("Scripting.FileSystemObject").DeleteFile WScript.ScriptFullName
            """;

    @Override
    public InstallKind kind() {
        return InstallKind.MSI;
    }

    @Override
    public boolean supported(Installation installation) {
        return installation.kind() == InstallKind.MSI;
    }

    @Override
    public boolean needsAuthentication() {
        return false;
    }

    @Override
    public Path prepare(Path verifiedDownload, String verifiedSha256, Installation installation) {
        return verifiedDownload;
    }

    /** @throws IllegalStateException when the installer script cannot be started */
    @Override
    public void launchAfterExit(Path msi, Installation installation) {
        var appExecutable = installation.appExecutable().toString();
        Path script = null;
        Path launcher = null;
        try {
            script = Files.createTempFile("astrolabe-update", ".cmd");
            launcher = Files.createTempFile("astrolabe-update", ".vbs");
            Files.writeString(script, script(ProcessHandle.current().pid()));
            Files.writeString(launcher, LAUNCHER);
            CommandRunner.startDetached(command(launcher, script, msi, appExecutable));
        } catch (IOException e) {
            for (var file : new Path[] {script, launcher}) {
                try {
                    if (file != null) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException ignored) {
                    // the temp directory is cleaned by the OS eventually
                }
            }
            throw new IllegalStateException("Could not start the installer: " + e.getMessage(), e);
        }
    }

    /**
     * The app has no console, so Windows gives a cmd script its own window, and the relaunched app keeps that window open as long as
     * it runs. wscript runs the script hidden (window style 0) and the JDK quotes the arguments. Not through {@code cmd /c start}:
     * start re-runs a .cmd as {@code cmd /K "script" "arg"}, whose outer-quote rule breaks a path with spaces.
     */
    static List<String> command(Path launcher, Path script, Path msi, String appExecutable) {
        return List.of("wscript.exe", "//B", "//Nologo", launcher.toString(), script.toString(), msi.toString(),
                Path.of(appExecutable).toAbsolutePath().toString());
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
                (goto) 2>nul & del "%%~f0" & exit
                """.formatted(pid, pid);
    }
}
