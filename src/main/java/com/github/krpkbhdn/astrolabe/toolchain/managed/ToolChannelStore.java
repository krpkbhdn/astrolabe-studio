package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The channel the user picked per tool, kept in {@code tools/channels.properties} so an uninstall does not forget it. */
@Component
public class ToolChannelStore {

    private static final Logger log = LoggerFactory.getLogger(ToolChannelStore.class);

    private final Path file;

    public ToolChannelStore(ManagedToolsDirectory directory) {
        this.file = directory.root().resolve("channels.properties");
    }

    /** VERIFIED when nothing, nothing readable or an unknown value is stored. */
    public synchronized ToolChannel selected(String toolId) {
        try {
            var stored = read().getProperty(toolId);
            return stored == null ? ToolChannel.VERIFIED : ToolChannel.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ToolChannel.VERIFIED;
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read {}: {}", file, e.getClass().getSimpleName());
            return ToolChannel.VERIFIED;
        }
    }

    /** @throws IOException when the stored file cannot be read, so the channels of the other tools are never overwritten */
    public synchronized void select(String toolId, ToolChannel channel) throws IOException {
        var all = read();
        all.setProperty(toolId, channel.name());
        Files.createDirectories(file.getParent());
        var temp = Files.createTempFile(file.getParent(), "channels", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                all.store(out, null);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Properties read() throws IOException {
        var all = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                all.load(in);
            } catch (RuntimeException e) {
                throw new IOException("Malformed " + file.getFileName(), e);
            }
        }
        return all;
    }
}
