package com.github.krpkbhdn.ccmanager.toolchain.managed;

import com.github.krpkbhdn.ccmanager.workspace.WindowsArgument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Writes the launcher in {@code tools/bin} that Claude's LSP plugin runs by command name: a .cmd on Windows, a sh script elsewhere. */
@Component
public class LauncherWriter {

    public enum Platform {
        WINDOWS, LINUX, MAC;

        public static Platform current() {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win") ? WINDOWS : os.contains("mac") ? MAC : LINUX;
        }

        String configDirectory() {
            return switch (this) {
                case WINDOWS -> "config_win";
                case LINUX -> "config_linux";
                case MAC -> "config_mac";
            };
        }
    }

    private static final String LAUNCHER_JAR_GLOB = "org.eclipse.equinox.launcher_*.jar";
    private static final String WINDOWS_WORKSPACE = "%WS%";
    private static final String POSIX_WORKSPACE = "$WS";

    private static final String BUNDLE_RESOURCE = "/toolchain/jdtls/dev.ccm.jdtls.output.jar";
    private static final String BUNDLE_JAR = "dev.ccm.jdtls.output.jar";
    private static final String OUR_FOLDER = ".ccm";
    private static final String APPLICATION = "dev.ccm.jdtls.output.app";
    private static final Pattern BUNDLES_LINE = Pattern.compile("^osgi\\.bundles=", Pattern.MULTILINE);
    private static final Pattern UNSAFE_REASON = Pattern.compile("[^A-Za-z0-9 .,_-]");
    private static final String MISSING_MESSAGE = "jdtls output bundle or its configuration is missing, reinstall jdtls";
    private static final String MISSING_WINDOWS = ">&2 echo " + MISSING_MESSAGE + " & exit /b 1\r\n";
    private static final String MISSING_POSIX = "{ echo '" + MISSING_MESSAGE + "' >&2; exit 1; }\n";

    /** Source of the bytes of our jdtls output bundle. */
    @FunctionalInterface
    public interface JdtlsBundle {
        byte[] bytes() throws IOException;
    }

    private final ManagedToolsDirectory directory;
    private final JdtlsBundle bundle;

    @Autowired
    public LauncherWriter(ManagedToolsDirectory directory) {
        this(directory, LauncherWriter::bundleFromClasspath);
    }

    LauncherWriter(ManagedToolsDirectory directory, JdtlsBundle bundle) {
        this.directory = directory;
        this.bundle = bundle;
    }

    private static byte[] bundleFromClasspath() throws IOException {
        try (var in = LauncherWriter.class.getResourceAsStream(BUNDLE_RESOURCE)) {
            if (in == null) {
                throw new IOException("The bundled jdtls output bundle " + BUNDLE_RESOURCE + " is missing from the application");
            }
            return in.readAllBytes();
        }
    }

    /** {@code binary} is run with all arguments passed on. */
    public Path writeBinary(String command, Path binary, Platform platform) throws IOException {
        return writeBinary(command, binary, List.of(), platform);
    }

    /** {@code arguments} come before the caller's own arguments, each quoted as one token. */
    public Path writeBinary(String command, Path binary, List<String> arguments, Platform platform) throws IOException {
        return writeBinary(command, binary, arguments, true, platform);
    }

    /** With {@code passCallerArguments} false the caller's own arguments are dropped, for a binary that rejects unknown ones. */
    public Path writeBinary(String command, Path binary, List<String> arguments, boolean passCallerArguments, Platform platform)
            throws IOException {
        var target = launcherPath(command, platform);
        var checked =new ArrayList<String>(List.of(target.toString(), binary.toString()));
        checked.addAll(arguments);
        requireSafe(platform, checked.toArray());
        var text = binaryLauncherText(binary, arguments, passCallerArguments, platform);
        return write(target, text, platform);
    }

    /**
     * The launcher file content. A launcher without the caller's arguments ends in {@code & exit /b} on its line, so cmd never reads
     * on past it when the file is rewritten while a session still runs the old one (cmd reads batch files by byte offset).
     */
    public static String binaryLauncherText(Path binary, List<String> arguments, boolean passCallerArguments, Platform platform) {
        var windows = platform == Platform.WINDOWS;
        var line = new StringBuilder(quoted(binary.toString(), windows));
        arguments.forEach(argument -> line.append(' ').append(quoted(argument, windows)));
        return windows
                ? "@echo off\r\n" + line + (passCallerArguments ? " %*" : " & exit /b") + "\r\n"
                : "#!/bin/sh\nexec " + line + (passCallerArguments ? " \"$@\"" : "") + "\n";
    }

    /** Our own java/Equinox start, so the Python wrapper of the archive is not needed. The workspace folder is derived from the working directory. */
    public Path writeJdtls(String command, Path install, Path stateDirectory, Platform platform) throws IOException {
        var target = launcherPath(command, platform);
        var jar = findLauncherJar(install);
        var ours = install.resolve(OUR_FOLDER);
        var copiedJar = ours.resolve(BUNDLE_JAR);
        var config = ours.resolve("config").resolve("config.ini");
        requireSafe(platform, target, install, stateDirectory, jar, copiedJar);
        installOurBundle(install, copiedJar, config, platform);
        var java = String.join(" ", jdtlsCommand(install, jar, stateDirectory, platform));
        var text = platform == Platform.WINDOWS
                ? "@echo off\r\nsetlocal\r\n"
                        + "if not exist \"" + copiedJar + "\" " + MISSING_WINDOWS
                        + "if not exist \"" + config + "\" " + MISSING_WINDOWS
                        + "set \"WS=%CD%\"\r\nset \"WS=%WS:\\=_%\"\r\nset \"WS=%WS:/=_%\"\r\nset \"WS=%WS::=_%\"\r\n" + java + "\r\n"
                : "#!/bin/sh\n[ -f " + posixQuote(copiedJar) + " ] && [ -f " + posixQuote(config) + " ] || " + MISSING_POSIX
                        + "WS=$(pwd | tr '/' '_')\nexec " + java + "\n";
        return write(target, text, platform);
    }

    /** A launcher that only reports why the installed version may not run, for a version or channel that is not allowed. */
    public Path writeRefusal(String command, String reason, Platform platform) throws IOException {
        var target = launcherPath(command, platform);
        var safe = UNSAFE_REASON.matcher(reason).replaceAll(" ");
        var text = platform == Platform.WINDOWS
                ? "@echo off\r\n>&2 echo " + safe + "\r\nexit /b 1\r\n"
                : "#!/bin/sh\necho " + posixQuote(safe) + " >&2\nexit 1\n";
        return write(target, text, platform);
    }

    private void installOurBundle(Path install, Path copiedJar, Path config, Platform platform) throws IOException {
        var bytes = bundle.bytes();
        Files.createDirectories(copiedJar.getParent());
        if (!Files.isRegularFile(copiedJar) || !Arrays.equals(Files.readAllBytes(copiedJar), bytes)) {
            Files.write(copiedJar, bytes);
        }
        var installed = install.resolve(platform.configDirectory()).resolve("config.ini");
        var original = Files.readString(installed);
        var matcher = BUNDLES_LINE.matcher(original);
        if (!matcher.find()) {
            throw new IOException("No osgi.bundles= line in " + installed);
        }
        var reference = "reference:file:" + copiedJar.toAbsolutePath().toString().replace('\\', '/') + "@4:start,";
        var generated = original.substring(0, matcher.end()) + reference + original.substring(matcher.end());
        Files.createDirectories(config.getParent());
        if (!Files.isRegularFile(config) || !Files.readString(config).equals(generated)) {
            Files.writeString(config, generated);
        }
    }

    /** The java command line as quoted tokens; the workspace token is a variable the script sets from the working directory. */
    List<String> jdtlsCommand(Path install, Path launcherJar, Path stateDirectory, Platform platform) {
        var windows = platform == Platform.WINDOWS;
        var workspace = stateDirectory.resolve("workspaces");
        var arguments = new ArrayList<String>(List.of(
                "java",
                "-Djdk.xml.maxGeneralEntitySizeLimit=0",
                "-Djdk.xml.totalEntitySizeLimit=0",
                "-Declipse.application=" + APPLICATION,
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dosgi.checkConfiguration=true",
                quoted("-Dosgi.sharedConfiguration.area=" + install.resolve(platform.configDirectory()), windows),
                "-Dosgi.sharedConfiguration.area.readOnly=true",
                "-Dosgi.configuration.cascaded=false",
                "-Djava.import.generatesMetadataFilesAtProjectRoot=false",
                "-Xms1G",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", quoted(launcherJar.toString(), windows),
                "-configuration", quoted(install.resolve(OUR_FOLDER).resolve("config").toString(), windows),
                "-data"));
        arguments.add(windows
                ? "\"" + workspace + "\\" + WINDOWS_WORKSPACE + "\""
                : posixQuote(workspace + "/") + "\"" + POSIX_WORKSPACE + "\"");
        arguments.add(windows ? "%*" : "\"$@\"");
        return arguments;
    }

    private Path launcherPath(String command, Platform platform) {
        if (!command.matches("[A-Za-z0-9._-]+") || command.startsWith(".")) {
            throw new IllegalArgumentException("Invalid launcher name: " + command);
        }
        return directory.launcherFile(command, platform == Platform.WINDOWS);
    }

    private static void requireSafe(Platform platform, Object... values) throws IOException {
        if (platform == Platform.WINDOWS) {
            for (var value : values) {
                if (!StandardCharsets.US_ASCII.newEncoder().canEncode(value.toString())) {
                    throw new IOException("The path " + value + " has non-ASCII characters, which cmd.exe cannot read from a launcher file. "
                            + "Use an ASCII-only data directory (cc-manager.data-dir).");
                }
            }
            WindowsArgument.requireSafeForBatchFile(java.util.stream.Stream.concat(java.util.stream.Stream.of("launcher.cmd"),
                    java.util.Arrays.stream(values).map(Object::toString)).toList());
        }
    }

    static boolean hasJdtlsLauncherJar(Path install) {
        try {
            findLauncherJar(install);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findLauncherJar(Path install) throws IOException {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(install.resolve("plugins"), LAUNCHER_JAR_GLOB)) {
            Path newest = null;
            for (var jar : jars) {
                if (newest == null || ManagedToolsDirectory.compareVersions(jar.getFileName().toString(), newest.getFileName().toString()) > 0) {
                    newest = jar;
                }
            }
            if (newest == null) {
                throw new IOException("No " + LAUNCHER_JAR_GLOB + " in " + install.resolve("plugins"));
            }
            return newest;
        }
    }

    private static Path write(Path target, String text, Platform platform) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target) && Files.readString(target).equals(text)) {
            return target;
        }
        var temp = Files.createTempFile(target.getParent(), ".launcher-", ".tmp");
        try {
            Files.writeString(temp, text);
            if (platform != Platform.WINDOWS && Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private static String quoted(String text, boolean windows) {
        return windows ? "\"" + text + "\"" : posixQuote(text);
    }

    private static String posixQuote(Object text) {
        return "'" + text.toString().replace("'", "'\\''") + "'";
    }
}
