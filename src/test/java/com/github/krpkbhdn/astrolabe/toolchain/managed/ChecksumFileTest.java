package com.github.krpkbhdn.astrolabe.toolchain.managed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ChecksumFileTest {

    private static final String HASH = "ab".repeat(32);

    @Test
    void matchingHashPassesWithNameColumnAndCrLf() {
        assertThatCode(() -> ChecksumFile.verify(HASH + "  tool.tar.gz\r\n", HASH)).doesNotThrowAnyException();
    }

    @Test
    void bareHexWithoutNewlineAndBinaryModeNameBothPass() {
        assertThatCode(() -> ChecksumFile.verify(HASH, HASH)).doesNotThrowAnyException();
        assertThatCode(() -> ChecksumFile.verify(HASH + " *kotlin-server-271.2085.0.win.zip", HASH)).doesNotThrowAnyException();
    }

    @Test
    void hexCaseDoesNotMatter() {
        assertThatCode(() -> ChecksumFile.verify(HASH.toUpperCase() + " *tool.zip", HASH)).doesNotThrowAnyException();
    }

    @Test
    void mismatchFails() {
        assertThatThrownBy(() -> ChecksumFile.verify("cd".repeat(32), HASH))
                .isInstanceOf(IOException.class).hasMessageContaining("mismatch");
    }

    @Test
    void emptyFileFails() {
        assertThatThrownBy(() -> ChecksumFile.verify("  \r\n", HASH)).isInstanceOf(IOException.class);
    }

    @Test
    void malformedTokenFails() {
        assertThatThrownBy(() -> ChecksumFile.verify("ab".repeat(31) + " tool.zip", HASH)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ChecksumFile.verify("zz".repeat(32), HASH)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ChecksumFile.verify("<html>Not Found</html>", HASH)).isInstanceOf(IOException.class);
    }
}
