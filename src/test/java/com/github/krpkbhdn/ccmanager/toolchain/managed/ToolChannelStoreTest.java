package com.github.krpkbhdn.ccmanager.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolChannelStoreTest {

    @TempDir
    Path dataDir;

    private ToolChannelStore store;
    private Path file;

    @BeforeEach
    void setUp() {
        var directory = new ManagedToolsDirectory(new AppProperties(dataDir));
        store = new ToolChannelStore(directory);
        file = directory.root().resolve("channels.properties");
    }

    @Test
    void defaultsToVerified() {
        assertThat(store.selected("jdtls")).isEqualTo(ToolChannel.VERIFIED);
    }

    @Test
    void roundTripsPerTool() throws IOException {
        store.select("jdtls", ToolChannel.LATEST);
        store.select("other", ToolChannel.VERIFIED);

        var reopened = new ToolChannelStore(new ManagedToolsDirectory(new AppProperties(dataDir)));
        assertThat(reopened.selected("jdtls")).isEqualTo(ToolChannel.LATEST);
        assertThat(reopened.selected("other")).isEqualTo(ToolChannel.VERIFIED);
    }

    @Test
    void aCorruptFileOrUnknownValueMeansVerified() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "jdtls=NIGHTLY\nbad=\\u12\n");

        assertThat(store.selected("jdtls")).isEqualTo(ToolChannel.VERIFIED);
        assertThat(store.selected("bad")).isEqualTo(ToolChannel.VERIFIED);
    }

    @Test
    void writingRepairsACorruptFileAndLeavesNoTempFile() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "\\u12");

        store.select("jdtls", ToolChannel.LATEST);

        assertThat(store.selected("jdtls")).isEqualTo(ToolChannel.LATEST);
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("channels.properties");
        }
    }
}
