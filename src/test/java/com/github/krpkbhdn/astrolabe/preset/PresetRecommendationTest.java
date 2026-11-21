package com.github.krpkbhdn.astrolabe.preset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class PresetRecommendationTest {

    private static final List<BundledPreset> LAYERS = List.of(layer("general"), layer("frontend", "frontend"),
            layer("angular", "angular", "frontend"));

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("angular", Set.of("angular", "frontend"), List.of("general", "angular")),
                Arguments.of("react", Set.of("frontend"), List.of("general", "frontend")),
                Arguments.of("empty", Set.<String>of(), List.of("general")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void recommendsMostSpecificLayers(String name, Set<String> traits, List<String> expected) {
        assertThat(PresetRecommendation.recommend(traits, LAYERS)).isEqualTo(expected);
    }

    private static BundledPreset layer(String key, String... match) {
        return new BundledPreset(key, 1, key, "", List.of(), List.of(match));
    }
}
