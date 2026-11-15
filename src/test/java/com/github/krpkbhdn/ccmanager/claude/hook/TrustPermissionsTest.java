package com.github.krpkbhdn.ccmanager.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import org.junit.jupiter.api.Test;

class TrustPermissionsTest {

    @Test
    void askAddsNothing() {
        assertThat(TrustPermissions.defaultMode(TrustLevel.ASK)).isEmpty();
        assertThat(TrustPermissions.allow(TrustLevel.ASK)).isEmpty();
        assertThat(TrustPermissions.deny(TrustLevel.ASK)).isEmpty();
    }

    @Test
    void trustedAcceptsEditsAndAllowsOnlyReadOnlyCommands() {
        assertThat(TrustPermissions.defaultMode(TrustLevel.TRUSTED)).contains("acceptEdits");
        assertThat(TrustPermissions.allow(TrustLevel.TRUSTED)).contains("Bash(git status)", "Bash(git status *)", "Bash(git branch --show-current)")
                .doesNotContain("Read", "Glob", "Grep", "Bash(echo *)", "Bash(./gradlew test *)", "Bash(npm test *)", "Bash(git branch *)")
                .allMatch(rule -> rule.startsWith("Bash("));
    }

    @Test
    void trustedDeniesSecretsCredentialStoresAndOutputWritingGit() {
        assertThat(TrustPermissions.deny(TrustLevel.TRUSTED)).contains("Read(**/.env*)", "Edit(**/*.pem)", "Write(~/.ssh/**)", "Read(~/.aws/**)",
                "Read(~/.claude/.credentials.json)", "Read(~/.cc-manager/**)", "Bash(cat *.env*)", "Bash(tail ~/.ssh/*)", "Bash(type *id_rsa*)",
                "Bash(git * --output*)", "Bash(git diff *--no-index*)", "Bash(git show *:*)", "Bash(git show *--output*)").doesNotContain("Bash(rm -rf *)");
    }

    @Test
    void autonomousBypassesPromptsButKeepsEveryDenyAndAddsDestructiveCommands() {
        assertThat(TrustPermissions.defaultMode(TrustLevel.AUTONOMOUS)).contains("bypassPermissions");
        assertThat(TrustPermissions.allow(TrustLevel.AUTONOMOUS)).isEmpty();
        assertThat(TrustPermissions.deny(TrustLevel.AUTONOMOUS)).containsAll(TrustPermissions.deny(TrustLevel.TRUSTED))
                .contains("Bash(rm -rf *)", "Bash(rm -fr *)", "Bash(git push --force*)", "Bash(git push * -f*)", "Bash(git push * +*)", "Bash(git reset --hard*)",
                        "Bash(git clean -f*)", "Bash(find * -delete*)")
                .doesNotContain("Bash(format *)", "Bash(del /s*)", "Bash(curl * | sh*)");
    }
}
