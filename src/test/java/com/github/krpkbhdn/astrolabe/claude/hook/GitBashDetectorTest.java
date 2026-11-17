package com.github.krpkbhdn.astrolabe.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GitBashDetectorTest {

    private static GitBashDetector detector(Map<String, String> env, Path... files) {
        return new GitBashDetector(env::get, Set.of(files)::contains);
    }

    @Test
    void aConfiguredPathCountsOnlyWhenTheFileExists() {
        var bash = Path.of("/gb/bin/bash.exe");

        assertThat(detector(Map.of("CLAUDE_CODE_GIT_BASH_PATH", bash.toString()), bash).present()).isTrue();
        assertThat(detector(Map.of("CLAUDE_CODE_GIT_BASH_PATH", bash.toString())).present()).isFalse();
        assertThat(detector(Map.of("CLAUDE_CODE_GIT_BASH_PATH", "  ")).present()).isFalse();
    }

    @Test
    void bashNextToGitInTheCmdLayoutIsFound() {
        var env = Map.of("PATH", "/x" + File.pathSeparator + Path.of("/git/cmd"));

        assertThat(detector(env, Path.of("/git/cmd/git.exe"), Path.of("/git/bin/bash.exe")).present()).isTrue();
    }

    @Test
    void bashTwoLevelsAboveTheMingwLayoutIsFound() {
        var env = Map.of("PATH", Path.of("/git/mingw64/bin").toString());

        assertThat(detector(env, Path.of("/git/mingw64/bin/git.exe"), Path.of("/git/bin/bash.exe")).present()).isTrue();
    }

    @Test
    void gitWithoutBashOrBashWithoutGitIsNotEnough() {
        var env = Map.of("PATH", Path.of("/git/cmd").toString());

        assertThat(detector(env, Path.of("/git/cmd/git.exe")).present()).isFalse();
        assertThat(detector(env, Path.of("/git/bin/bash.exe")).present()).isFalse();
    }

    @Test
    void noPathAndNoConfigurationMeansNoGitBash() {
        assertThat(detector(Map.of()).present()).isFalse();
    }
}
