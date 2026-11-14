package com.github.krpkbhdn.ccmanager.codemode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Files for the images tools return to a script of one session; names are built from a counter and a whitelisted extension only.
 * On POSIX systems the folder and files are owner-only; Windows keeps the default access of the data folder.
 */
final class SessionImages {

    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of("image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;
    private final int maxImages;
    private final long maxTotalBytes;
    private int count;
    private long totalBytes;
    private boolean closed;

    SessionImages(Path directory, int maxImages, long maxTotalBytes) {
        this.directory = directory;
        this.maxImages = maxImages;
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * @return {type:'image', mimeType, path}, or the same with an {@code omitted} reason when the image itself cannot be stored
     * @throws IllegalStateException when the session ended or already holds its allowed number or total size of images
     */
    synchronized ObjectNode store(String mimeType, String base64) {
        if (closed) {
            throw new IllegalStateException(ServerConnections.SESSION_ENDED);
        }
        var node = JSON.createObjectNode().put("type", "image").put("mimeType", mimeType);
        var extension = EXTENSIONS.get(mimeType);
        if (extension == null) {
            return node.put("omitted", "unsupported image type");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return node.put("omitted", "invalid image data");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            return node.put("omitted", "image larger than " + MAX_IMAGE_BYTES / (1024 * 1024) + " MB");
        }
        if (count >= maxImages || totalBytes + bytes.length > maxTotalBytes) {
            throw new IllegalStateException("This session already holds the most images it may keep (" + maxImages + " images or "
                    + maxTotalBytes / (1024 * 1024) + " MB); the image was not stored");
        }
        var file = directory.resolve("img-" + (count + 1) + "." + extension);
        try {
            Files.createDirectories(directory, ownerOnly("rwx------"));
            Files.write(Files.createFile(file, ownerOnly("rw-------")), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        count++;
        totalBytes += bytes.length;
        return node.put("path", file.toAbsolutePath().toString());
    }

    /** After this no file is written, so a store still in flight when the session is forgotten cannot recreate its deleted folder. */
    synchronized void close() {
        closed = true;
    }

    private static FileAttribute<?>[] ownerOnly(String permissions) {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))}
                : new FileAttribute<?>[0];
    }
}
