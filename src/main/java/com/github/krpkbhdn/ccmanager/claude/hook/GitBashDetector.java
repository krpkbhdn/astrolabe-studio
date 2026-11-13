package com.github.krpkbhdn.ccmanager.claude.hook;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Whether Claude Code will find Git Bash on Windows: the path it is told about, or bash.exe in the install root of a git.exe on the PATH. */
final class GitBashDetector {

    private static final String GIT_BASH_PATH_ENV = "CLAUDE_CODE_GIT_BASH_PATH";
    private static final String PATH_ENV = "PATH";
    private static final String GIT_EXECUTABLE = "git.exe";
    private static final String BASH_EXECUTABLE = "bash.exe";
    private static final String BIN_DIRECTORY = "bin";
    private static final int LAYOUT_DEPTH = 2;

    private final UnaryOperator<String> environment;
    private final Predicate<Path> isFile;

    GitBashDetector(UnaryOperator<String> environment, Predicate<Path> isFile) {
        this.environment = environment;
        this.isFile = isFile;
    }

    static GitBashDetector system() {
        return new GitBashDetector(System::getenv, Files::isRegularFile);
    }

    /** Git for Windows puts git.exe in cmd or in mingw64\bin, so bash.exe sits in bin one or two levels above its folder. */
    boolean present() {
        var configured = environment.apply(GIT_BASH_PATH_ENV);
        if (configured != null && !configured.isBlank()) {
            return isFile(configured);
        }
        var path = environment.apply(PATH_ENV);
        if (path == null) {
            return false;
        }
        for (var directory : path.split(File.pathSeparator)) {
            if (hasGitWithBash(directory)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGitWithBash(String directory) {
        try {
            var folder = Path.of(directory);
            if (!isFile.test(folder.resolve(GIT_EXECUTABLE))) {
                return false;
            }
            var root = folder;
            for (var level = 0; level < LAYOUT_DEPTH && root.getParent() != null; level++) {
                root = root.getParent();
                if (isFile.test(root.resolve(BIN_DIRECTORY).resolve(BASH_EXECUTABLE))) {
                    return true;
                }
            }
            return false;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private boolean isFile(String path) {
        try {
            return isFile.test(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
