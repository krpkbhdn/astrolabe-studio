package com.github.krpkbhdn.astrolabe.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TerminalTextTest {

    private static final String ESC = "\u001B";

    @Test
    void npmSpinnerRedrawnInPlaceEndsWithTheFinalText() {
        var raw = ESC + "[1t" + ESC + "[c" + ESC + "[?25l" + ESC + "]0;npm" + ESC + "\\|" + ESC + "[1G" + ESC + "[0K/" + ESC + "[1G" + ESC + "[0K-"
                + ESC + "[1G" + ESC + "[0Kadded 3 packages\r\n";

        assertThat(TerminalText.render(raw)).isEqualTo("added 3 packages\n");
    }

    @Test
    void spinnerFramesOverwriteEachOther() {
        assertThat(TerminalText.render("-\r\\\r|\r/\r\nadded 3 packages")).isEqualTo("/\nadded 3 packages");
    }

    @Test
    void shorterRedrawLeavesTheOldTailUnlessTheLineIsErased() {
        assertThat(TerminalText.render("Downloading 100%\rDone")).isEqualTo("Doneloading 100%");
        assertThat(TerminalText.render("Downloading 100%\r" + ESC + "[KDone")).isEqualTo("Done");
        assertThat(TerminalText.render("Downloading 100%\r" + ESC + "[0KDone")).isEqualTo("Done");
    }

    @Test
    void wingetProgressBarKeepsOnlyTheLastRedraw() {
        var raw = "\r   ███▒▒▒  30%\r   ██████▒  60%\r   ███████ 100%\r\nSuccessfully installed\r\n";

        assertThat(TerminalText.render(raw)).isEqualTo("   ███████ 100%\nSuccessfully installed\n");
    }

    @Test
    void eraseWholeLineThenReturnRedraws() {
        assertThat(TerminalText.render("old text long\r" + ESC + "[2K\rnew")).isEqualTo("new");
    }

    @Test
    void eraseToStartBlanksUpToTheCursor() {
        assertThat(TerminalText.render("abcdef" + ESC + "[3D" + ESC + "[1K")).isEqualTo("    ef");
    }

    @Test
    void cursorUpRedrawsATwoLineStatusBlock() {
        var raw = "step 1\r\nstep 2\r\n" + ESC + "[2A\r" + ESC + "[Kstep 1 done\r\n" + ESC + "[Kstep 2 done\r\nnext";

        assertThat(TerminalText.render(raw)).isEqualTo("step 1 done\nstep 2 done\nnext");
    }

    @Test
    void cursorUpAboveTheFirstLineClampsAndCursorDownStaysInsideTheBuffer() {
        assertThat(TerminalText.render("a\r\nb" + ESC + "[9AX" + ESC + "[9BY")).isEqualTo("aX\nb Y");
    }

    @Test
    void absolutePositionMapsIntoTheRenderedLinesAndClearScreenEmptiesThem() {
        assertThat(TerminalText.render("one\r\ntwo\r\n" + ESC + "[1;2Hx")).isEqualTo("oxe\ntwo\n");
        assertThat(TerminalText.render("one\r\ntwo" + ESC + "[2J\rthree")).isEqualTo("three");
    }

    @Test
    void colorsModesAndTitlesAreDropped() {
        var raw = ESC + "[?25l" + ESC + "]0;title\u0007" + ESC + "]0;other" + ESC + "\\" + ESC + "[1;32mOK" + ESC + "[0m fine" + ESC + "[?25h\u0007";

        assertThat(TerminalText.render(raw)).isEqualTo("OK fine");
    }

    @Test
    void crLfIsOneNewlineAndBackspaceMovesLeft() {
        assertThat(TerminalText.render("a\r\nb\r\n\r\nc")).isEqualTo("a\nb\n\nc");
        assertThat(TerminalText.render("abc\b\bX")).isEqualTo("aXc");
    }

    @Test
    void tabIsASingleSpace() {
        assertThat(TerminalText.render("a\tb")).isEqualTo("a b");
    }

    @Test
    void incompleteTrailingSequenceIsDropped() {
        assertThat(TerminalText.render("done" + ESC + "[3")).isEqualTo("done");
        assertThat(TerminalText.render("done" + ESC)).isEqualTo("done");
        assertThat(TerminalText.render("done" + ESC + "]0;ti")).isEqualTo("done");
    }

    @Test
    void onlyTheLastLinesAreKept() {
        var raw = "line\r\n".repeat(TerminalText.MAX_LINES * 3) + "end";

        var lines = TerminalText.render(raw).split("\n", -1);

        assertThat(lines).hasSize(TerminalText.MAX_LINES);
        assertThat(lines[lines.length - 1]).isEqualTo("end");
    }

    @Test
    void threeMegabytesRenderQuickly() {
        var chunk = ESC + "[1;32mprogress" + ESC + "[0m 42%\r\u001B[K download line\r\n";
        var raw = chunk.repeat(3 * 1024 * 1024 / chunk.length());

        assertThat(Duration.ofMillis(measureMillis(raw))).isLessThan(Duration.ofSeconds(5));
    }

    private static long measureMillis(String raw) {
        long start = System.nanoTime();
        TerminalText.render(raw);
        return (System.nanoTime() - start) / 1_000_000;
    }
}
