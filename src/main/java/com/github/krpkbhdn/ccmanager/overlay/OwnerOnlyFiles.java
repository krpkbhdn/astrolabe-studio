package com.github.krpkbhdn.ccmanager.overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Files the group and others must not read (session tokens, project config): created with the permissions where POSIX exists, else the ACL is cut to the owner before any content is written. */
public final class OwnerOnlyFiles {

    private static final String POSIX = "posix";
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";

    private OwnerOnlyFiles() {}

    /** Replaces [file] atomically; the parent directory must exist. */
    public static void write(Path file, String content) throws IOException {
        var posix = file.getFileSystem().supportedFileAttributeViews().contains(POSIX);
        var temp = posix
                ? Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMISSIONS)))
                : Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            if (!posix) {
                restrictAclToOwner(temp);
            }
            Files.writeString(temp, content);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Creates [directory] and each missing parent owner-only (POSIX mode, else an owner-only ACL that children inherit); an existing leaf keeps its ACL and gets the POSIX mode. */
    public static void createDirectory(Path directory) throws IOException {
        var parent = directory.getParent();
        if (parent != null && !Files.exists(parent)) {
            createDirectory(parent);
        }
        var existed = Files.isDirectory(directory);
        Files.createDirectories(directory);
        if (directory.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(DIRECTORY_PERMISSIONS));
        } else if (!existed) {
            restrictAclToOwner(directory, EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT));
        }
    }

    private static void restrictAclToOwner(Path file) throws IOException {
        restrictAclToOwner(file, EnumSet.noneOf(AclEntryFlag.class));
    }

    /** Replaces the inherited ACL (and every other entry) with one full-control entry for the file's owner. */
    private static void restrictAclToOwner(Path file, Set<AclEntryFlag> flags) throws IOException {
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view != null) {
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner()).setFlags(flags)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
}
