package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.workspace.CommandRunner;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Works out from {@code jpackage.app-path} how this app was installed; empty when it cannot be updated in place. Only a definitive answer
 * is kept for the run; when the package manager query timed out or could not start, the next call after the retry backoff asks again.
 */
@Component
public class InstallationDetector {

    private static final String APP_PATH_PROPERTY = "jpackage.app-path";
    private static final String ARCH_PROPERTY = "os.arch";
    private static final String APP_BUNDLE_SUFFIX = ".app";
    private static final Path TAR_GZ_DIRECTORY = Path.of(".local", "opt", "astrolabe-studio");

    private final HostEnvironment host;
    private final Function<String, String> properties;
    private final Function<List<String>, Ownership> ownership;
    private final Duration retryBackoff;
    private final Clock clock;
    /** Null until a definitive answer exists. */
    private volatile Detection known;
    private volatile Instant retryAfter = Instant.MIN;

    /** What a package manager query said about the executable; {@code UNKNOWN} (timeout, cannot start, other exit) is never kept. */
    enum Ownership { OWNED, NOT_OWNED, UNKNOWN }

    /** @param installation null when this app is not an installation that can be updated */
    private record Detection(Installation installation, boolean definitive) {
    }

    @Autowired
    InstallationDetector(HostEnvironment host, UpdateProperties updates) {
        this(host, System::getProperty, command -> query(command, updates.queryTimeout()), updates.detectionRetryBackoff(), Clock.systemUTC());
    }

    InstallationDetector(HostEnvironment host, Function<String, String> properties, Function<List<String>, Ownership> ownership, Duration retryBackoff,
            Clock clock) {
        this.host = host;
        this.properties = properties;
        this.ownership = ownership;
        this.retryBackoff = retryBackoff;
        this.clock = clock;
    }

    public Optional<Installation> detect() {
        var cached = known;
        if (cached != null) {
            return Optional.ofNullable(cached.installation());
        }
        var now = clock.instant();
        if (now.isBefore(retryAfter)) {
            return Optional.empty();
        }
        var detection = detectNow();
        if (detection.definitive()) {
            known = detection;
        } else {
            retryAfter = now.plus(retryBackoff);
        }
        return Optional.ofNullable(detection.installation());
    }

    private Detection detectNow() {
        var appPath = properties.apply(APP_PATH_PROPERTY);
        var arch = arch(properties.apply(ARCH_PROPERTY));
        if (appPath == null || arch == null) {
            return definitive(null);
        }
        var executable = real(Path.of(appPath));
        return switch (host.os()) {
            case WINDOWS -> definitive(installation(InstallKind.MSI, arch, executable.getParent(), executable));
            case MACOS -> definitive(bundleOf(executable).map(app -> installation(InstallKind.DMG, arch, app, executable)).orElse(null));
            case LINUX -> linux(arch, executable);
        };
    }

    private static Detection definitive(Installation installation) {
        return new Detection(installation, true);
    }

    private Detection linux(String arch, Path executable) {
        // both sides resolved, so a symlinked HOME (or a symlinked launcher) cannot make a tar.gz install look like a system one
        var home = real(host.home());
        var tarRoot = home.resolve(TAR_GZ_DIRECTORY);
        if (executable.startsWith(tarRoot)) {
            return definitive(installation(InstallKind.TAR_GZ, arch, tarRoot, executable));
        }
        if (executable.startsWith(home)) {
            return definitive(null);
        }
        var path = executable.toString();
        var dpkg = ownership.apply(List.of("dpkg", "-S", path));
        var rpm = dpkg == Ownership.OWNED ? Ownership.NOT_OWNED : ownership.apply(List.of("rpm", "-qf", path));
        InstallKind kind = dpkg == Ownership.OWNED ? InstallKind.DEB : rpm == Ownership.OWNED ? InstallKind.RPM : null;
        // jpackage layout: <root>/bin/<launcher>
        var bin = executable.getParent();
        var found = kind == null || bin == null ? null : installation(kind, arch, bin.getParent(), executable);
        return new Detection(found, kind != null || dpkg == Ownership.NOT_OWNED && rpm == Ownership.NOT_OWNED);
    }

    /** Null when there is no root folder. */
    private static Installation installation(InstallKind kind, String arch, Path root, Path executable) {
        return root == null ? null : new Installation(kind, arch, root, executable);
    }

    /** Symlinks resolved; the normalised absolute path when the file cannot be resolved (it need not exist). */
    private static Path real(Path path) {
        var normal = path.toAbsolutePath().normalize();
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return normal;
        }
    }

    private static Optional<Path> bundleOf(Path path) {
        for (var current = path; current != null; current = current.getParent()) {
            var name = current.getFileName();
            if (name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(APP_BUNDLE_SUFFIX)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private static String arch(String osArch) {
        if (osArch == null) {
            return null;
        }
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "x64";
            default -> null;
        };
    }

    /** Exit 0 = owned, exit 1 = not owned (dpkg -S and rpm -qf); a timeout, another exit code or a command that cannot start is unknown. */
    private static Ownership query(List<String> command, Duration timeout) {
        try {
            var result = CommandRunner.run(command, null, timeout);
            if (result.timedOut() || result.truncated()) {
                return Ownership.UNKNOWN;
            }
            return switch (result.exitCode()) {
                case 0 -> Ownership.OWNED;
                case 1 -> Ownership.NOT_OWNED;
                default -> Ownership.UNKNOWN;
            };
        } catch (IOException e) {
            return Ownership.UNKNOWN;
        }
    }
}
