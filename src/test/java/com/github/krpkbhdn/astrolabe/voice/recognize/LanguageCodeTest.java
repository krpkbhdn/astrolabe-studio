package com.github.krpkbhdn.astrolabe.voice.recognize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LanguageCodeTest {

    @Test
    void normalizeTrimsLowercasesAndCollapsesDuplicates() {
        assertThat(LanguageCode.normalize(" UK , en ,uk")).contains("uk,en");
        assertThat(LanguageCode.normalize("auto")).contains("auto");
        assertThat(LanguageCode.normalize("DE")).contains("de");
    }

    @Test
    void invalidSpecsAreRejected() {
        for (var spec : new String[] {"auto,en", "uk,xx", "uk,", ",uk", "uk,,en", "", "uk,en,de,fr,pl", null}) {
            assertThat(LanguageCode.normalize(spec)).as("%s", spec).isEmpty();
        }
    }

    @Test
    void onlyTheStoredFormIsKnownAndValidated() {
        assertThat(LanguageCode.isKnown("uk,en")).isTrue();
        assertThat(LanguageCode.isKnown("uk, en")).isFalse();
        assertThat(LanguageCode.isKnown("UK,en")).isFalse();
        assertThat(LanguageCode.isKnown("uk,uk")).isFalse();
        assertThatThrownBy(() -> LanguageCode.validate("auto,en")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LanguageCode.validate("uk,en")).isEqualTo("uk,en");
    }

    @Test
    void candidatesSplitTheStoredSpec() {
        assertThat(LanguageCode.candidates("uk,en")).containsExactly("uk", "en");
        assertThat(LanguageCode.candidates("uk")).containsExactly("uk");
    }

    @Test
    void pickLanguageTakesTheMostProbableCandidateAndTheFirstOnATie() {
        var ids = Map.of("uk", 1, "en", 2, "pl", 3);
        float[] probabilities = {0f, 0.2f, 0.3f, 0.9f};

        assertThat(FfmRecognizer.pickLanguage(probabilities, List.of("uk", "en"), ids::get)).isEqualTo("en");
        assertThat(FfmRecognizer.pickLanguage(new float[] {0f, 0.4f, 0.4f, 0f}, List.of("en", "uk"), ids::get)).isEqualTo("en");
    }

    @Test
    void pickLanguageFallsBackToTheFirstCandidateWithoutValidIds() {
        assertThat(FfmRecognizer.pickLanguage(new float[] {0.5f}, List.of("uk", "en"), code -> -1)).isEqualTo("uk");
        assertThat(FfmRecognizer.pickLanguage(new float[] {0.5f}, List.of("uk", "en"), code -> 7)).isEqualTo("uk");
    }
}
