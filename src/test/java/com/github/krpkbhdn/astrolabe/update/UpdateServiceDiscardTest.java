package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.download.VerifiedDownload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Black-box checks of where {@link UpdateService#discard} may delete, of the result file and of the launch guards. */
class UpdateServiceDiscardTest {

    @TempDir
    Path root;

    private Path dataDir;
    private Path updates;
    private Path download;
    private final PlatformUpdater updater = mock(PlatformUpdater.class);

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createDirectories(root.resolve("data"));
        updates = Files.createDirectories(dataDir.resolve("updates"));
        download = Files.writeString(updates.resolve("app.deb"), "verified");
    }

    private UpdateService service(Optional<Installation> installation) {
        var detector = mock(InstallationDetector.class);
        when(detector.detect()).thenReturn(installation);
        var properties = TestUpdateProperties.of("1.0.0", "o/r", "http://127.0.0.1:1", 6, 5, Duration.ofSeconds(1));
        return new UpdateService(properties, new AppProperties(dataDir), JsonMapper.builder().build(), List.of(updater), detector, () -> mock(ReleaseSignature.class),
                new VerifiedDownload());
    }

    private Path victim() throws Exception {
        var outside = Files.createDirectories(root.resolve("victim"));
        Files.writeString(outside.resolve("keep.txt"), "precious");
        return outside;
    }

    @Test
    void discardLeavesAPathThatLeavesTheUpdatesFolderThroughDotDot() throws Exception {
        var outside = victim();
        var sneaky = updates.resolve("..").resolve("..").resolve("victim");

        service(Optional.empty()).discard(new PreparedUpdate(sneaky, download));

        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void discardLeavesAPathOutsideEveryAllowedLocation() throws Exception {
        var outside = victim();

        service(Optional.empty()).discard(new PreparedUpdate(outside, download));

        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void discardOfTheUpdatesFolderItselfDeletesNothing() throws Exception {
        service(Optional.empty()).discard(new PreparedUpdate(updates, download));

        assertThat(download).exists();
    }

    @Test
    void discardOfASymlinkInsideTheUpdatesFolderRemovesTheLinkNotTheTarget() throws Exception {
        var outside = victim();
        var link = updates.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }

        service(Optional.empty()).discard(new PreparedUpdate(link, download));

        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void discardThroughASymlinkedSubfolderDoesNotReachIntoTheTarget() throws Exception {
        var outside = victim();
        var link = updates.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }

        service(Optional.empty()).discard(new PreparedUpdate(link.resolve("keep.txt"), download));

        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void discardOfTheStagedDownloadItselfKeepsTheDownload() {
        service(Optional.empty()).discard(new PreparedUpdate(download, download));

        assertThat(download).exists();
    }

    @Test
    void discardOfAPackageTempFolderOnlyRemovesAnAstrolabeUpdateFolder() throws Exception {
        var staged = Files.createDirectories(root.resolve("astrolabe-update-abc"));
        var file = Files.writeString(staged.resolve("pkg.deb"), "x");
        var foreign = Files.createDirectories(root.resolve("other-folder"));
        var foreignFile = Files.writeString(foreign.resolve("pkg.deb"), "x");
        var service = service(Optional.empty());

        service.discard(new PreparedUpdate(foreignFile, download));
        service.discard(new PreparedUpdate(file, download));

        assertThat(foreignFile).exists();
        assertThat(staged).doesNotExist();
    }

    @Test
    void cancelRemovesTheMacStagingBesideTheAppAndNeverTheAppItself() throws Exception {
        var apps = Files.createDirectories(root.resolve("Applications"));
        var app = Files.createDirectories(apps.resolve("Astrolabe Studio.app").resolve("Contents"));
        var staged = Files.createDirectories(apps.resolve(".Astrolabe Studio.app.update").resolve("Contents"));
        Files.writeString(staged.resolve("f"), "x");
        var installation = new Installation(InstallKind.DMG, "arm64", apps.resolve("Astrolabe Studio.app"), app.resolve("MacOS-launcher"));

        service(Optional.of(installation)).discard(new PreparedUpdate(apps.resolve(".Astrolabe Studio.app.update"), download));

        assertThat(apps.resolve(".Astrolabe Studio.app.update")).doesNotExist();
        assertThat(app).exists();
        assertThat(download).exists();
    }

    @Test
    void anUnknownPathIsRefusedByPrepareAndNeverReachesTheUpdater() throws Exception {
        var stranger = Files.writeString(root.resolve("stranger.deb"), "x");
        var installation = new Installation(InstallKind.DEB, "x64", Path.of("root"), Path.of("root", "bin", "app"));
        when(updater.kind()).thenReturn(InstallKind.DEB);
        when(updater.supported(installation)).thenReturn(true);
        var service = service(Optional.of(installation));

        assertThatThrownBy(() -> service.prepareInstall(stranger)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not verified");
        assertThatThrownBy(() -> service.prepareInstall(root.resolve("missing.deb"))).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(updater, org.mockito.Mockito.never()).prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anUnclassifiedInstallationCannotPrepareOrLaunch() {
        var service = service(Optional.empty());

        assertThatThrownBy(() -> service.prepareInstall(download)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.launchAfterExit(new PreparedUpdate(download, download))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theResultFileIsReadOnceAndHostileContentNeverReachesTheOutcome() throws Exception {
        var file = updates.resolve(PosixUpdateScript.RESULT_FILE);
        Files.writeString(file, "FAILED ../../etc/passwd\nOK");

        var outcome = service(Optional.empty()).lastOutcome();

        assertThat(outcome).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(UpdateOutcome.Status.FAILED);
            assertThat(found.step()).isEmpty();
        });
        assertThat(file).doesNotExist();
    }

    @Test
    void anOversizedOrBinaryResultFileDoesNotCrashTheStart() throws Exception {
        Files.write(updates.resolve(PosixUpdateScript.RESULT_FILE), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1, 2});

        assertThat(service(Optional.empty()).lastOutcome()).isEmpty();
    }

    @Test
    void aResultFileThatIsADirectoryIsIgnored() throws Exception {
        Files.createDirectories(updates.resolve(PosixUpdateScript.RESULT_FILE));

        assertThat(service(Optional.empty()).lastOutcome()).isEmpty();
    }

    @Test
    void lowercaseOrPaddedCodesAreNotAccepted() {
        assertThat(UpdateService.parseOutcome("ok")).isEmpty();
        assertThat(UpdateService.parseOutcome("OK extra")).isEmpty();
        assertThat(UpdateService.parseOutcome("FAILEDinstall")).hasValueSatisfying(found -> assertThat(found.step()).isEqualTo("install"));
    }
}
