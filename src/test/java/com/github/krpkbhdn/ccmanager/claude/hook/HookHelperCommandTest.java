package com.github.krpkbhdn.ccmanager.claude.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HookHelperCommandTest {

    private static final String URL = "http://127.0.0.1:5555/statusline/abc-1";
    private static final UnaryOperator<String> NO_SHORT_NAME = path -> path;

    private static HookHelperCommand helper(String launcher, String location, boolean windows, UnaryOperator<String> shortPath, boolean gitBash) {
        return new HookHelperCommand(Optional.ofNullable(launcher), location, windows, shortPath, gitBash);
    }

    @Test
    void packagedLauncherRunsItselfWithThePostFlagAndNoToken() {
        var helper = helper("/opt/ccm/ccm-app", "/classes", false, NO_SHORT_NAME, false);

        assertThat(helper.shellCommand(URL)).hasValue("/opt/ccm/ccm-app --post-hook " + URL);
        assertThat(helper.execForm(URL)).hasValue(new HookHelperCommand.Exec("/opt/ccm/ccm-app", List.of("--post-hook", URL)));
    }

    @Test
    void theTokenFileFollowsTheUrlInBothFormsAndTheTokenItselfIsNeverPassed() {
        var file = java.nio.file.Path.of("/data/hooks/1/abc-1.token");
        var helper = helper("/opt/ccm/ccm-app", "/classes", false, NO_SHORT_NAME, false);

        assertThat(helper.execForm(URL, file).orElseThrow().args()).containsExactly("--post-hook", URL, "--token-file", file.toString());
        assertThat(helper.shellCommand(URL, file)).hasValue("/opt/ccm/ccm-app --post-hook " + URL + " --token-file " + file);
        var windows = helper("C:\\jdk\\bin\\java.exe", "C:\\cls", true, path -> path.replace("Users", "USERS~1"), false);
        assertThat(windows.shellCommand(URL, java.nio.file.Path.of("C:\\Users\\x\\abc-1.token")).orElseThrow()).endsWith(URL + " --token-file C:/USERS~1/x/abc-1.token");
        assertThat(windows.shellCommand(URL, java.nio.file.Path.of("C:\\a$b\\abc-1.token"))).isEmpty();
    }

    @Test
    void aJavaLauncherRunsTheHelperClassFromItsCodeSourceWithFastStartupOptions() {
        var helper = helper("/jdk/bin/java", "/build/classes/java/main", false, NO_SHORT_NAME, false);

        assertThat(helper.shellCommand(URL)).hasValue("/jdk/bin/java -Xshare:auto -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -cp /build/classes/java/main "
                + HookPoster.class.getName() + " --post-hook " + URL);
        assertThat(helper.execForm(URL).orElseThrow().args()).containsExactly("-Xshare:auto", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-cp",
                "/build/classes/java/main", HookPoster.class.getName(), "--post-hook", URL);
    }

    @Test
    void execFormKeepsPathsWithSpacesAsPlainArguments() {
        var exec = helper("C:\\Program Files\\Ccm\\java.exe", "C:\\my classes", true, NO_SHORT_NAME, false).execForm(URL).orElseThrow();

        assertThat(exec.command()).isEqualTo("C:\\Program Files\\Ccm\\java.exe");
        assertThat(exec.args()).contains("C:\\my classes");
    }

    @Test
    void execFormKeepsNonAsciiAndSpacedLauncherUrlAndClasspathAsSingleArguments() {
        var launcher = "C:/Program Files/\u041c\u0456\u0439 app/CC Manager.exe";
        var exec = helper(launcher, "C:/x y/\u0436", false, NO_SHORT_NAME, false).execForm("http://127.0.0.1:1/s?a=b c").orElseThrow();

        assertThat(exec.command()).isEqualTo(launcher);
        assertThat(exec.args()).containsExactly(HookPoster.FLAG, "http://127.0.0.1:1/s?a=b c");
        var dev = helper("C:/Program Files/Java/bin/java.exe", "D:/my classes/\u0436", false, NO_SHORT_NAME, false).execForm("http://h/").orElseThrow();
        assertThat(dev.args()).containsSequence("-cp", "D:/my classes/\u0436", HookPoster.class.getName(), HookPoster.FLAG, "http://h/");
    }

    @Test
    void posixShellCommandQuotesSpacesAndQuotes() {
        assertThat(helper("/opt/my app/it's", "/x", false, NO_SHORT_NAME, false).shellCommand(URL)).hasValue("'/opt/my app/it'\\''s' --post-hook " + URL);
    }

    @Test
    void windowsShellCommandUsesShortNamesWithForwardSlashesAndNeedsNoQuotes() {
        var helper = helper("C:\\Program Files\\Ccm\\java.exe", "C:\\my classes", true,
                path -> path.replace("Program Files", "PROGRA~1").replace("my classes", "MYCLAS~1"), false);

        assertThat(helper.shellCommand(URL).orElseThrow()).startsWith("C:/PROGRA~1/Ccm/java.exe ").contains("-cp C:/MYCLAS~1 ").doesNotContain("\"");
        assertThat(helper.shellFormNote()).isEmpty();
        assertThat(helper.unavailableReason()).isEmpty();
    }

    @Test
    void windowsWithoutAShortNameUsesTheQuotedFormOnlyWithGitBashAndSaysSo() {
        var helper = helper("C:\\Program Files\\Ccm\\ccm.exe", "C:\\x", true, NO_SHORT_NAME, true);

        assertThat(helper.shellCommand(URL)).hasValue("\"C:/Program Files/Ccm/ccm.exe\" --post-hook " + URL);
        assertThat(helper.shellFormNote()).isPresent();
        assertThat(helper.unavailableReason()).isEmpty();
    }

    @Test
    void windowsWithoutAShortNameAndWithoutGitBashWritesNoShellCommandAndGivesTheReason() {
        var helper = helper("C:\\Program Files\\Ccm\\ccm.exe", "C:\\x", true, NO_SHORT_NAME, false);

        assertThat(helper.shellCommand(URL)).isEmpty();
        assertThat(helper.unavailableReason()).get().asString().contains("short DOS name").contains("Git Bash");
        assertThat(helper.shellFormNote()).isEmpty();
        assertThat(helper.execForm(URL)).isPresent();
    }

    @Test
    void aClasspathThatNeedsQuotesAloneQuotesOnlyThatArgumentAndGivesNoNote() {
        var helper = helper("C:\\jdk\\bin\\java.exe", "C:\\my classes", true, NO_SHORT_NAME, false);

        assertThat(helper.shellCommand(URL).orElseThrow()).startsWith("C:/jdk/bin/java.exe ").contains("-cp \"C:/my classes\" ");
        assertThat(helper.shellFormNote()).isEmpty();
        assertThat(helper.unavailableReason()).isEmpty();
    }

    @Test
    void theNoteFollowsTheLauncherOnlyWhateverGitBashSays() {
        for (var gitBash : List.of(true, false)) {
            var classpathOnly = helper("C:\\jdk\\bin\\java.exe", "C:\\my classes", true, NO_SHORT_NAME, gitBash);
            var launcherQuoted = helper("C:\\Program Files\\jdk\\java.exe", "C:\\cls", true, NO_SHORT_NAME, gitBash);

            assertThat(classpathOnly.shellFormNote()).isEmpty();
            assertThat(classpathOnly.shellCommand(URL)).isPresent();
            assertThat(launcherQuoted.shellFormNote().isPresent()).isEqualTo(gitBash);
            assertThat(launcherQuoted.shellCommand(URL).isPresent()).isEqualTo(gitBash);
        }
    }

    @Test
    void windowsPathsWithDollarBacktickOrQuoteCannotBeQuotedSafelyAndOmitTheShellCommand() {
        for (var launcher : List.of("C:\\a b\\$x.exe", "C:\\a b\\`x.exe", "C:\\a b\\\"x.exe")) {
            var helper = helper(launcher, "C:\\x", true, NO_SHORT_NAME, true);

            assertThat(helper.shellCommand(URL)).as(launcher).isEmpty();
            assertThat(helper.unavailableReason()).as(launcher).isPresent();
        }
        assertThat(helper("C:\\jdk\\bin\\java.exe", "C:\\$cls", true, NO_SHORT_NAME, true).shellCommand(URL)).isEmpty();
    }

    @Test
    void withoutALauncherThereIsNoCommandAndAReason() {
        var helper = helper(null, "/x", false, NO_SHORT_NAME, false);

        assertThat(helper.shellCommand(URL)).isEmpty();
        assertThat(helper.execForm(URL)).isEmpty();
        assertThat(helper.unavailableReason()).isPresent();
    }

    @Test
    void theRunningApplicationHasALauncher() {
        assertThat(new HookHelperCommand().execForm(URL)).isPresent();
    }
}
