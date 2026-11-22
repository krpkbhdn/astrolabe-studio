package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.github.krpkbhdn.astrolabe.update.InstallationDetector.Ownership;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstallationDetectorTest {

    private static final Path HOME = Path.of("/home/user").toAbsolutePath();

    private final Map<String, String> properties = new HashMap<>(Map.of("os.arch", "amd64"));
    private final List<List<String>> commands = new ArrayList<>();
    private Set<String> succeeding = Set.of();

    private Optional<Installation> detect(OperatingSystem os, String appPath) {
        var host = mock(HostEnvironment.class);
        when(host.os()).thenReturn(os);
        when(host.home()).thenReturn(HOME);
        if (appPath != null) {
            properties.put("jpackage.app-path", appPath);
        }
        return detector(host, command -> {
            commands.add(command);
            return succeeding.contains(command.getFirst()) ? Ownership.OWNED : Ownership.NOT_OWNED;
        }).detect();
    }

    private static final Duration BACKOFF = Duration.ofSeconds(30);

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private InstallationDetector detector(HostEnvironment host, java.util.function.Function<List<String>, Ownership> ownership) {
        return new InstallationDetector(host, properties::get, ownership, BACKOFF, clock);
    }

    private HostEnvironment linuxHost() {
        var host = mock(HostEnvironment.class);
        when(host.os()).thenReturn(OperatingSystem.LINUX);
        when(host.home()).thenReturn(HOME);
        properties.put("jpackage.app-path", "/opt/astrolabe-studio/bin/Astrolabe Studio");
        return host;
    }

    @TempDir
    Path temp;

    @Test
    void aSymlinkedHomeStillClassifiesATarGzInstallAsOneNotAsSystem() throws IOException {
        var realHome = Files.createDirectories(temp.resolve("real-home"));
        var executable = Files.createFile(Files.createDirectories(realHome.resolve(".local/opt/astrolabe-studio/bin")).resolve("app"));
        var linkedHome = temp.resolve("linked-home");
        try {
            Files.createSymbolicLink(linkedHome, realHome);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no symlinks on this host");
        }
        var host = mock(HostEnvironment.class);
        when(host.os()).thenReturn(OperatingSystem.LINUX);
        when(host.home()).thenReturn(linkedHome);
        properties.put("jpackage.app-path", executable.toString());

        var found = detector(host, command -> {
            commands.add(command);
            return Ownership.NOT_OWNED;
        }).detect();

        assertThat(found).hasValueSatisfying(installation -> assertThat(installation.kind()).isEqualTo(InstallKind.TAR_GZ));
        assertThat(commands).isEmpty();
    }

    @Test
    void aTimedOutQueryIsNotKeptAndIsAskedAgainAfterTheBackoff() {
        var answers = new ArrayList<>(List.of(Ownership.UNKNOWN, Ownership.OWNED));
        var detector = detector(linuxHost(), command -> {
            commands.add(command);
            return command.getFirst().equals("dpkg") ? answers.removeFirst() : Ownership.NOT_OWNED;
        });

        assertThat(detector.detect()).isEmpty();
        var firstRound = commands.size();
        assertThat(detector.detect()).isEmpty();
        assertThat(commands).hasSize(firstRound);

        now = now.plus(BACKOFF);
        assertThat(detector.detect()).hasValueSatisfying(i -> assertThat(i.kind()).isEqualTo(InstallKind.DEB));
    }

    @Test
    void aDefinitiveAnswerIsKeptForTheRunForOwnedAndForNotOwned() {
        var owned = detector(linuxHost(), command -> {
            commands.add(command);
            return Ownership.OWNED;
        });
        owned.detect();
        owned.detect();
        assertThat(commands).hasSize(1);

        commands.clear();
        var notOwned = detector(linuxHost(), command -> {
            commands.add(command);
            return Ownership.NOT_OWNED;
        });
        assertThat(notOwned.detect()).isEmpty();
        now = now.plus(BACKOFF).plus(BACKOFF);
        assertThat(notOwned.detect()).isEmpty();
        assertThat(commands).hasSize(2);
    }

    private static Path abs(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    @Test
    void windowsIsMsiWithItsInstallDirectory() {
        var found = detect(OperatingSystem.WINDOWS, "C:\\Users\\u\\AppData\\Local\\Astrolabe Studio\\Astrolabe Studio.exe");

        assertThat(found).hasValueSatisfying(i -> {
            assertThat(i.kind()).isEqualTo(InstallKind.MSI);
            assertThat(i.root().getFileName()).hasToString("Astrolabe Studio");
        });
        assertThat(commands).isEmpty();
    }

    @Test
    void macPathWithSpacesIsDmgRootedAtTheBundle() {
        var found = detect(OperatingSystem.MACOS, "/Applications/Astrolabe Studio.app/Contents/MacOS/Astrolabe Studio");

        assertThat(found).hasValueSatisfying(i -> {
            assertThat(i.kind()).isEqualTo(InstallKind.DMG);
            assertThat(i.root()).isEqualTo(abs("/Applications/Astrolabe Studio.app"));
            assertThat(i.appExecutable()).isEqualTo(abs("/Applications/Astrolabe Studio.app/Contents/MacOS/Astrolabe Studio"));
        });
    }

    @Test
    void macOutsideABundleIsEmpty() {
        assertThat(detect(OperatingSystem.MACOS, "/opt/tool/bin/Astrolabe Studio")).isEmpty();
    }

    @Test
    void linuxOwnedByDpkgIsDeb() {
        succeeding = Set.of("dpkg");

        var found = detect(OperatingSystem.LINUX, "/opt/astrolabe-studio/bin/Astrolabe Studio");

        assertThat(found).hasValueSatisfying(i -> {
            assertThat(i.kind()).isEqualTo(InstallKind.DEB);
            assertThat(i.root()).isEqualTo(abs("/opt/astrolabe-studio"));
        });
        assertThat(commands).containsExactly(List.of("dpkg", "-S", abs("/opt/astrolabe-studio/bin/Astrolabe Studio").toString()));
    }

    @Test
    void linuxOwnedByRpmIsRpm() {
        succeeding = Set.of("rpm");

        var found = detect(OperatingSystem.LINUX, "/opt/astrolabe-studio/bin/Astrolabe Studio");

        assertThat(found).hasValueSatisfying(i -> assertThat(i.kind()).isEqualTo(InstallKind.RPM));
        assertThat(commands).hasSize(2);
    }

    @Test
    void linuxSystemTarGzOrFailingQueriesAreEmpty() {
        assertThat(detect(OperatingSystem.LINUX, "/opt/astrolabe-studio/bin/Astrolabe Studio")).isEmpty();
    }

    @Test
    void linuxTarGzInHomeIsTarGzWithoutQueryingPackageManagers() {
        succeeding = Set.of("dpkg", "rpm");

        var found = detect(OperatingSystem.LINUX, HOME + "/.local/opt/astrolabe-studio/bin/Astrolabe Studio");

        assertThat(found).hasValueSatisfying(i -> {
            assertThat(i.kind()).isEqualTo(InstallKind.TAR_GZ);
            assertThat(i.root()).isEqualTo(HOME.resolve(".local/opt/astrolabe-studio"));
        });
        assertThat(commands).isEmpty();
    }

    @Test
    void linuxElsewhereInHomeIsEmptyWithoutQueries() {
        succeeding = Set.of("dpkg");

        assertThat(detect(OperatingSystem.LINUX, HOME + "/apps/astrolabe/bin/Astrolabe Studio")).isEmpty();
        assertThat(commands).isEmpty();
    }

    @Test
    void devRunWithoutAppPathIsEmpty() {
        assertThat(detect(OperatingSystem.LINUX, null)).isEmpty();
        assertThat(detect(OperatingSystem.WINDOWS, null)).isEmpty();
    }

    @Test
    void archIsMapped() {
        for (var entry : Map.of("aarch64", "arm64", "arm64", "arm64", "amd64", "x64", "x86_64", "x64").entrySet()) {
            properties.put("os.arch", entry.getKey());
            assertThat(detect(OperatingSystem.WINDOWS, "C:\\App\\a.exe")).hasValueSatisfying(i -> assertThat(i.arch()).isEqualTo(entry.getValue()));
        }
    }

    @Test
    void unknownOrMissingArchIsEmpty() {
        properties.put("os.arch", "riscv64");
        assertThat(detect(OperatingSystem.WINDOWS, "C:\\App\\a.exe")).isEmpty();
        properties.remove("os.arch");
        assertThat(detect(OperatingSystem.WINDOWS, "C:\\App\\a.exe")).isEmpty();
    }
}
