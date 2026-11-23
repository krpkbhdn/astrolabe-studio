package com.github.krpkbhdn.astrolabe.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UpdatePropertiesTest {

    @Test
    void acceptsTheDefaults() {
        assertThatCode(() -> TestUpdateProperties.of("", "o/r", "http://x", 6, 15, java.time.Duration.ofSeconds(5))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} = {2}")
    @MethodSource("invalid")
    void rejectsInvalidValueNamingTheProperty(String field, Supplier<UpdateProperties> create, String shown) {
        assertThatThrownBy(create::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(kebab(field));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("checkIntervalHours", (java.util.function.Supplier<UpdateProperties>) () -> TestUpdateProperties.of("", "o/r", "http://x", 0, 15, java.time.Duration.ofSeconds(5)), "0"),
                Arguments.of("checkIntervalHours", (java.util.function.Supplier<UpdateProperties>) () -> TestUpdateProperties.of("", "o/r", "http://x", -1, 15, java.time.Duration.ofSeconds(5)), "-1"),
                Arguments.of("requestTimeoutSeconds", (java.util.function.Supplier<UpdateProperties>) () -> TestUpdateProperties.of("", "o/r", "http://x", 6, 0, java.time.Duration.ofSeconds(5)), "0"));
    }

    @org.junit.jupiter.api.Test
    void installTunablesDefaultToTheFormerConstantsAndRejectNonPositiveValues() {
        var defaults = TestUpdateProperties.defaults();

        assertThat(defaults.pidWaitTimeout()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(defaults.pidKillGrace()).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(defaults.hdiutilAttachTimeout()).isEqualTo(java.time.Duration.ofMinutes(2));
        assertThat(defaults.hdiutilDetachTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(defaults.dittoTimeout()).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(defaults.archiveMaxEntries()).isEqualTo(100_000);
        assertThat(defaults.archiveMaxUnpackedBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
        assertThat(defaults.maxInstallerBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(defaults.maxLogBytes()).isEqualTo(1024 * 1024);
        assertThat(defaults.detectionRetryBackoff()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThatThrownBy(() -> new UpdateProperties("", "o/r", "http://x", 6, 15, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 1, 1, 0, 1,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(4))).hasMessageContaining("max-installer-bytes");
        assertThatThrownBy(() -> new UpdateProperties("", "o/r", "http://x", 6, 15, java.time.Duration.ofSeconds(5), java.time.Duration.ZERO,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 1, 1, 1, 1,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(4)))
                .hasMessageContaining("pid-wait-timeout");
    }

    @Test
    void bindsThroughTheCanonicalConstructorWithDefaultsAndOverrides() {
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("astrolabe.update.version", "1.2.3", "astrolabe.update.check-interval-hours", "9",
                                "astrolabe.update.max-installer-bytes", "5", "astrolabe.update.max-log-bytes", "7")));
        var bound = binder.bind("astrolabe.update", UpdateProperties.class).get();

        assertThat(bound.version()).isEqualTo("1.2.3");
        assertThat(bound.checkIntervalHours()).isEqualTo(9);
        assertThat(bound.repository()).isEqualTo("krpkbhdn/astrolabe-studio");
        assertThat(bound.pidWaitTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(bound.maxInstallerBytes()).isEqualTo(5);
        assertThat(bound.maxLogBytes()).isEqualTo(7);
        assertThat(bound.detectionRetryBackoff()).isEqualTo(Duration.ofSeconds(30));

        var empty = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of("astrolabe.update.x", "y")));
        assertThat(empty.bindOrCreate("astrolabe.update", UpdateProperties.class)).isEqualTo(TestUpdateProperties.defaults()).returns(false, UpdateProperties::enabled);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([A-Z])", "-$1").toLowerCase();
    }
}
