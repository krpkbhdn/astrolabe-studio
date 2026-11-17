package com.github.krpkbhdn.astrolabe.toolchain.managed;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Unpacks a downloaded ZIP or TAR.GZ into a directory without trusting the archive: no path may leave the target,
 * devices are refused, entry count and real unpacked bytes are capped, and only the owner-exec bit is kept.
 */
public final class SafeArchiveExtractor {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int ZIP_TYPE_MASK = 0170000;
    private static final int ZIP_SYMLINK = 0120000;
    private static final int ZIP_DIRECTORY = 0040000;
    private static final int ZIP_REGULAR = 0100000;
    private static final int OWNER_EXEC_BIT = 0100;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * Extracts {@code archive} into {@code targetDir} and returns the number of entries written.
     * A single common top folder is stripped. Any violation throws an {@link IOException}.
     */
    public int extract(Path archive, Path targetDir, boolean tarGz, long maxEntries, long maxUnpackedBytes)
            throws IOException {
        List<List<String>> names = new ArrayList<>();
        walk(archive, tarGz, (item, in) -> {
            if (names.size() >= maxEntries) {
                throw new IOException("Archive has more than " + maxEntries + " entries");
            }
            names.add(segments(item.name()));
        });
        int strip = commonTopFolder(names) ? 1 : 0;
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Run run = new Run(root, root.toRealPath(), strip, maxUnpackedBytes);
        walk(archive, tarGz, run::write);
        return run.written;
    }

    private static boolean commonTopFolder(List<List<String>> names) {
        String top = null;
        for (List<String> segments : names) {
            if (segments.isEmpty()) {
                continue;
            }
            if (top == null) {
                top = segments.get(0);
            } else if (!top.equals(segments.get(0))) {
                return false;
            }
        }
        // a file named like the top folder itself means there is no folder to strip
        return top != null && names.stream().anyMatch(s -> s.size() > 1);
    }

    private static List<String> segments(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe archive entry: " + printable(name));
        }
        List<String> result = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..") || segment.indexOf(':') >= 0) {
                throw new IOException("Unsafe archive entry: " + printable(name));
            }
            result.add(segment);
        }
        return result;
    }

    private static String printable(String name) {
        return name.replaceAll("\\p{Cntrl}", "?");
    }

    private static void checkInterrupt() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Extraction interrupted");
        }
    }

    private enum Kind { FILE, DIRECTORY, SYMLINK, HARDLINK }

    private record Item(String name, Kind kind, int mode, String link) {
    }

    private interface EntrySink {
        void accept(Item item, InputStream content) throws IOException;
    }

    private static void walk(Path archive, boolean tarGz, EntrySink sink) throws IOException {
        if (tarGz) {
            walkTar(archive, sink);
        } else {
            walkZip(archive, sink);
        }
    }

    private static void walkTar(Path archive, EntrySink sink) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw, BUFFER_SIZE))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                checkInterrupt();
                sink.accept(tarItem(entry), tar);
            }
        }
    }

    private static Item tarItem(TarArchiveEntry entry) throws IOException {
        Kind kind;
        // TarArchiveEntry.isFile() is true for anything not ending in '/', so devices are refused first
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
            throw new IOException("Unsupported archive entry type: " + printable(entry.getName()));
        } else if (entry.isDirectory()) {
            kind = Kind.DIRECTORY;
        } else if (entry.isSymbolicLink()) {
            kind = Kind.SYMLINK;
        } else if (entry.isLink()) {
            kind = Kind.HARDLINK;
        } else if (entry.isFile()) {
            kind = Kind.FILE;
        } else {
            throw new IOException("Unsupported archive entry type: " + printable(entry.getName()));
        }
        return new Item(entry.getName(), kind, entry.getMode(), entry.getLinkName());
    }

    private static void walkZip(Path archive, EntrySink sink) throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                checkInterrupt();
                ZipArchiveEntry entry = entries.nextElement();
                int type = entry.getUnixMode() & ZIP_TYPE_MASK;
                if (entry.isDirectory() || type == ZIP_DIRECTORY) {
                    sink.accept(new Item(entry.getName(), Kind.DIRECTORY, entry.getUnixMode(), null), null);
                } else if (type == ZIP_SYMLINK) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        String target = new String(in.readNBytes(BUFFER_SIZE), StandardCharsets.UTF_8);
                        sink.accept(new Item(entry.getName(), Kind.SYMLINK, entry.getUnixMode(), target), null);
                    }
                } else if (type == 0 || type == ZIP_REGULAR) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        sink.accept(new Item(entry.getName(), Kind.FILE, entry.getUnixMode(), null), in);
                    }
                } else {
                    throw new IOException("Unsupported archive entry type: " + printable(entry.getName()));
                }
            }
        }
    }

    private static final class Run {
        private final Path root;
        private final Path realRoot;
        private final int strip;
        private final long maxBytes;
        private long bytes;
        private int written;

        Run(Path root, Path realRoot, int strip, long maxBytes) {
            this.root = root;
            this.realRoot = realRoot;
            this.strip = strip;
            this.maxBytes = maxBytes;
        }

        void write(Item item, InputStream content) throws IOException {
            List<String> parts = segments(item.name());
            if (parts.size() <= strip) {
                return;
            }
            List<String> kept = parts.subList(strip, parts.size());
            Path target = prepareTarget(kept);
            switch (item.kind()) {
                case DIRECTORY -> Files.createDirectories(target);
                case FILE -> writeFile(target, content, item.mode());
                case SYMLINK -> writeSymlink(target, item.link());
                case HARDLINK -> copyHardlink(target, item.link(), item.mode());
            }
            written++;
        }

        private Path prepareTarget(List<String> kept) throws IOException {
            Path current = root;
            for (int i = 0; i < kept.size(); i++) {
                current = current.resolve(kept.get(i));
                if (i < kept.size() - 1 && Files.isSymbolicLink(current)) {
                    throw new IOException("Archive writes through a link: " + printable(kept.get(i)));
                }
            }
            if (!current.normalize().startsWith(root)) {
                throw new IOException("Unsafe archive entry: " + printable(String.join("/", kept)));
            }
            Files.createDirectories(current.getParent());
            return current;
        }

        private void writeFile(Path target, InputStream content, int mode) throws IOException {
            deleteLinkAt(target);
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = content.read(buffer)) >= 0) {
                    countBytes(read);
                    out.write(buffer, 0, read);
                }
            }
            applyMode(target, mode);
        }

        private void countBytes(long count) throws IOException {
            checkInterrupt();
            bytes += count;
            if (bytes > maxBytes) {
                throw new IOException("Archive unpacks to more than " + maxBytes + " bytes");
            }
        }

        private static void deleteLinkAt(Path target) throws IOException {
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
            }
        }

        private static void applyMode(Path target, int mode) throws IOException {
            if (!POSIX) {
                return;
            }
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            if ((mode & OWNER_EXEC_BIT) != 0) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(target, permissions);
        }

        private void writeSymlink(Path link, String linkTarget) throws IOException {
            if (linkTarget == null || linkTarget.isEmpty() || linkTarget.indexOf('\0') >= 0
                    || linkTarget.indexOf('\\') >= 0 || linkTarget.startsWith("/")) {
                throw new IOException("Unsafe link in archive: " + printable(String.valueOf(linkTarget)));
            }
            List<String> parts = new ArrayList<>();
            boolean leading = true;
            for (String segment : linkTarget.split("/")) {
                if (segment.isEmpty() || segment.equals(".")) {
                    continue;
                }
                if (segment.indexOf(':') >= 0 || (segment.equals("..") && !leading)) {
                    throw new IOException("Unsafe link in archive: " + printable(linkTarget));
                }
                leading &= segment.equals("..");
                parts.add(segment);
            }
            // ".." only up front, so it always climbs real directories and the check below holds for chains of links
            Path resolved = link.getParent().toRealPath().resolve(String.join("/", parts)).normalize();
            if (parts.isEmpty() || !resolved.startsWith(realRoot)) {
                throw new IOException("Link leaves the target folder: " + printable(linkTarget));
            }
            deleteLinkAt(link);
            if (POSIX) {
                Files.createSymbolicLink(link, link.getFileSystem().getPath(String.join("/", parts)));
            } else if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                // shortcut: no symlinks without privilege on Windows, a link to a not yet extracted target is skipped
                copyChecked(resolved, link);
            }
        }

        private void copyHardlink(Path target, String linkName, int mode) throws IOException {
            List<String> parts = segments(linkName == null ? "" : linkName);
            if (parts.size() <= strip) {
                throw new IOException("Unsafe link in archive: " + printable(String.valueOf(linkName)));
            }
            Path source = root;
            for (String part : parts.subList(strip, parts.size())) {
                source = source.resolve(part);
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("Link goes through a link: " + printable(linkName));
                }
            }
            if (!source.normalize().startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Link leaves the target folder: " + printable(linkName));
            }
            deleteLinkAt(target);
            copyChecked(source, target);
            applyMode(target, mode);
        }

        private void copyChecked(Path source, Path target) throws IOException {
            countBytes(Files.size(source));
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
