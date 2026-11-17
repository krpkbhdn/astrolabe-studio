package com.github.krpkbhdn.astrolabe.toolchain;

import com.github.krpkbhdn.astrolabe.workspace.WindowsArgument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the command line for a tool binary; on Windows an npm .cmd or .bat shim needs cmd.exe and a metacharacter check. */
final class ShimLaunch {

    /** Thrown when a Windows batch shim path or argument holds cmd metacharacters; its message is not for users. */
    static final class UnsafeLaunchException extends RuntimeException {
        UnsafeLaunchException(Throwable cause) {
            super("unsafe path for a .cmd launcher", cause);
        }
    }

    private ShimLaunch() {}

    /** @throws UnsafeLaunchException when a Windows batch shim path or argument holds cmd metacharacters */
    static List<String> command(boolean windows, Path binary, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(binary.toString());
        command.addAll(arguments);
        var name = binary.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!windows || !(name.endsWith(".cmd") || name.endsWith(".bat"))) {
            return command;
        }
        try {
            WindowsArgument.requireSafeForBatchFile(command);
        } catch (IllegalStateException e) {
            throw new UnsafeLaunchException(e);
        }
        command.addAll(0, List.of("cmd.exe", "/c"));
        return command;
    }
}
