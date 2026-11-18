package com.github.krpkbhdn.astrolabe.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;


class SessionImagesTest extends CodeModeFixture {

    @Test
    void imageStoreInFlightAfterTheSessionWasForgottenWritesNothing() {
        var closed = new SessionImages(dataDir.resolve("gone"), 5, 1_000_000);
        closed.close();

        assertThatThrownBy(() -> closed.store("image/png", Base64.getEncoder().encodeToString(PNG))).isInstanceOf(IllegalStateException.class);
        assertThat(dataDir.resolve("gone")).doesNotExist();
    }

    @Test
    void imageCapsRefuseFurtherImagesWithAClearScriptError() {
        var data = Base64.getEncoder().encodeToString(PNG);
        var capped = new SessionImages(dataDir.resolve("capped"), 2, 1_000);

        capped.store("image/png", data);
        capped.store("image/png", data);

        assertThatThrownBy(() -> capped.store("image/png", data)).isInstanceOf(IllegalStateException.class).hasMessageContaining("2 images");
        var bySize = new SessionImages(dataDir.resolve("by-size"), 10, PNG.length * 2L - 1);
        bySize.store("image/png", data);
        assertThatThrownBy(() -> bySize.store("image/png", data)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void imageFilesAreOwnerOnlyWhereSupported() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var images = new SessionImages(dataDir.resolve("img"), 50, 100L * 1024 * 1024);

        var stored = images.store("image/png", Base64.getEncoder().encodeToString(PNG));

        var file = Path.of(stored.path("path").asString());
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
    }
}
