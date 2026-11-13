package com.github.krpkbhdn.ccmanager.claude.install;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClaudeVersionTest {

    @ParameterizedTest
    @CsvSource({"2.1.139,2.1.139,true", "2.1.140,2.1.139,true", "2.2.0,2.1.139,true", "3.0.0,2.1.139,true", "2.10.0,2.9.99,true",
            "2.1.138,2.1.139,false", "2.0.999,2.1.139,false", "1.99.99,2.1.139,false", "2.1.310 (Claude Code),2.1.139,true"})
    void comparesNumericallyPartByPart(String version, String minimum, boolean expected) {
        assertThat(ClaudeVersion.atLeast(version, minimum)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "''", "unknown", "2.1"}, nullValues = "null")
    void anUnknownOrUnparsableVersionIsNeverEnough(String version) {
        assertThat(ClaudeVersion.atLeast(version, "0.0.0")).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"2.1.139|true", "2.1.138|false", "2.1.139 (Claude Code)|true", "2.1.138 (Claude Code)|false", "2.10.0|true",
            "2.1|false", "  2.1.139  |true", "v2.1.139|false", "2.1.139-beta.1|true", "2.1.138-rc1|false", "2.1.139+build5|true", "2.1.1390|true",
            "02.01.0139|true", "claude 2.1.139|false", "2.1.139.4|true"})
    void gateAtTheSessionStartExecFormVersion(String version, boolean expected) {
        assertThat(ClaudeVersion.atLeast(version, "2.1.139")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"''", "'   '", "-", "99999999999.1.1", "2.1.99999999999"}, nullValues = "-")
    void garbageNullAndOverflowNeverThrowAndAreNeverEnough(String version) {
        assertThat(ClaudeVersion.atLeast(version, "2.1.139")).isFalse();
    }
}
