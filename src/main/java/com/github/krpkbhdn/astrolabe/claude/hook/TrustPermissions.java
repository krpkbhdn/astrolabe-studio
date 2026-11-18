package com.github.krpkbhdn.astrolabe.claude.hook;

import com.github.krpkbhdn.astrolabe.project.TrustLevel;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The permissions part of a session's settings that a project's trust level adds. Deny rules stay in force in every mode, bypassPermissions included,
 * but they match command text and paths only, so they are a best-effort guard and no sandbox.
 */
final class TrustPermissions {

    private static final List<String> READ_ONLY_COMMANDS = List.of("git status", "git diff", "git log", "git show", "ls", "pwd");
    private static final List<String> EXACT_COMMANDS = List.of("git branch --list", "git branch --show-current");
    private static final List<String> GIT_FILE_LEAKS = List.of("git * --output*", "git diff *--no-index*", "git show *:*", "git show *--output*");
    private static final List<String> SECRET_FILES = List.of(".env", "**/.env*", "**/*.pem", "**/id_rsa*", "~/.ssh/**");
    private static final List<String> CREDENTIAL_STORES = List.of("~/.aws/**", "~/.config/gh/**", "~/.claude/.credentials.json", "~/.astrolabe-studio/**",
            "~/.gnupg/**", "~/.kube/**", "~/.npmrc", "~/.netrc");
    private static final List<String> FILE_TOOLS = List.of("Read", "Edit", "Write");
    private static final List<String> FILE_VIEWERS = List.of("cat", "type", "less", "more", "head", "tail");
    private static final List<String> SECRET_ARGUMENTS = List.of("*.env*", "~/.ssh/*", "*id_rsa*");
    private static final List<String> DESTRUCTIVE_COMMANDS = List.of("Bash(rm -rf *)", "Bash(rm -fr *)", "Bash(rm -r -f *)", "Bash(git push --force*)",
            "Bash(git push -f*)", "Bash(git push * --force*)", "Bash(git push * -f*)", "Bash(git push * +*)", "Bash(git reset --hard*)",
            "Bash(git clean -f*)", "Bash(find * -delete*)");

    private TrustPermissions() {}

    static Optional<String> defaultMode(TrustLevel level) {
        return switch (level) {
            case ASK -> Optional.empty();
            case TRUSTED -> Optional.of("acceptEdits");
            case AUTONOMOUS -> Optional.of("bypassPermissions");
        };
    }

    /** Only read-only commands; file reads inside the project need no rule because acceptEdits covers them. */
    static List<String> allow(TrustLevel level) {
        if (level != TrustLevel.TRUSTED) {
            return List.of();
        }
        return Stream.concat(READ_ONLY_COMMANDS.stream().flatMap(command -> Stream.of(bash(command), bash(command + " *"))),
                EXACT_COMMANDS.stream().map(TrustPermissions::bash)).toList();
    }

    static List<String> deny(TrustLevel level) {
        if (level == TrustLevel.ASK) {
            return List.of();
        }
        var rules = Stream.of(secretFileRules(), GIT_FILE_LEAKS.stream().map(TrustPermissions::bash), secretViewerRules());
        var common = rules.flatMap(stream -> stream).toList();
        return level == TrustLevel.TRUSTED ? common : Stream.concat(common.stream(), DESTRUCTIVE_COMMANDS.stream()).toList();
    }

    private static Stream<String> secretFileRules() {
        return FILE_TOOLS.stream().flatMap(tool -> Stream.concat(SECRET_FILES.stream(), CREDENTIAL_STORES.stream()).map(path -> tool + "(" + path + ")"));
    }

    private static Stream<String> secretViewerRules() {
        return FILE_VIEWERS.stream().flatMap(viewer -> SECRET_ARGUMENTS.stream().map(argument -> bash(viewer + " " + argument)));
    }

    private static String bash(String command) {
        return "Bash(" + command + ")";
    }
}
