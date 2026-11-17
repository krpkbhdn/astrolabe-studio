package com.github.krpkbhdn.ccmanager.codemode.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ArgumentPreviewTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ArgumentPreview.Preview preview(Object value) {
        return ArgumentPreview.of(MAPPER.valueToTree(value));
    }

    private static java.util.Map<String, Object> map(Object... pairs) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    @Test
    void moreSecretLookingKeyNamesAreMaskedWhateverTheCase() {
        var shown = preview(map("Cookie", "a", "my_credentials", "b", "bearer", "c", "private-key", "d", "SessionId", "e", "jwt", "f",
                "passphrase", "g", "X-Signature", "h", "private_key", "i", "plain", "ok")).node();

        for (var key : new String[] {"Cookie", "my_credentials", "bearer", "private-key", "SessionId", "jwt", "passphrase", "X-Signature", "private_key"}) {
            assertThat(shown.get(key).asString()).as(key).isEqualTo(ArgumentPreview.MASK);
        }
        assertThat(shown.get("plain").asString()).isEqualTo("ok");
    }

    @Test
    void zeroWidthCharactersInsideAKeyDoNotHideItFromTheSecretCheck() {
        var shown = preview(map("to\u200Bken", "secret-value")).node();

        assertThat(shown.toString()).doesNotContain("secret-value");
    }

    @Test
    void valuesThatLookLikeSecretsAreMaskedInsideStrings() {
        var jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N";
        var hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        var shown = preview(map("a", "Authorization: Bearer abcdef123456 end", "b", "key sk-abcdefghijklmnop1234 end", "c", "ghp_abcdefghijklmnopqrstuvwx1234",
                "d", "jwt " + jwt, "e", "digest " + hex, "f", "a normal sentence with words")).node();

        assertThat(shown.get("a").asString()).isEqualTo("Authorization: *** end");
        assertThat(shown.get("b").asString()).isEqualTo("key *** end");
        assertThat(shown.get("c").asString()).isEqualTo(ArgumentPreview.MASK);
        assertThat(shown.get("d").asString()).isEqualTo("jwt ***");
        assertThat(shown.get("e").asString()).isEqualTo("digest ***");
        assertThat(shown.get("f").asString()).isEqualTo("a normal sentence with words");
    }

    @Test
    void longRunOfOneLetterIsNotMistakenForASecret() {
        var text = "x".repeat(60);

        assertThat(preview(map("t", text)).node().get("t").asString()).isEqualTo(text);
    }

    @Test
    void controlBidiAndSeparatorCharactersAreReplacedAndCountAsTruncation() {
        for (var hidden : new String[] {"\u202E", "\u2066", "\u2028", "\u0000", "\u200B"}) {
            var result = preview(map("k", "a" + hidden + "b"));

            assertThat(result.node().get("k").asString()).as("U+%04X", (int) hidden.charAt(0)).isEqualTo("a b");
            assertThat(result.truncated()).isTrue();
        }
    }

    @Test
    void plainNewlinesAndTabsShowAsSpacesWithoutCountingAsTruncation() {
        var result = preview(map("k", "a\nb\r\n\tc"));

        assertThat(result.node().get("k").asString()).isEqualTo("a b   c");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void hiddenCharactersInKeysAreReplacedToo() {
        var result = preview(map("na\u202Eme", "v"));

        assertThat(result.node().has("na me")).isTrue();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void plainTextIsNotTruncated() {
        assertThat(preview(map("k", "plain value 1")).truncated()).isFalse();
    }

    @Test
    void distinctKeysThatLookTheSameAfterCuttingDoNotOverwriteEachOther() {
        var prefix = "k".repeat(ArgumentPreview.MAX_TEXT);
        var result = preview(map(prefix + "1", "first", prefix + "2", "second"));

        var values = new ArrayList<String>();
        result.node().forEach(value -> values.add(value.asString()));
        assertThat(values).containsExactly("first", "second");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void realKeyNamedLikeTheOverflowMarkerCannotPassForIt() {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put(ArgumentPreview.OVERFLOW_KEY, "5 more fields");
        for (int i = 0; i < ArgumentPreview.MAX_ITEMS + 2; i++) {
            fields.put("k" + i, i);
        }
        JsonNode shown = preview(fields).node();

        assertThat(shown.get(ArgumentPreview.OVERFLOW_KEY).asString()).isEqualTo(ArgumentPreview.moreFields(3));
        assertThat(shown.get("... (2)").asString()).isEqualTo("5 more fields");
    }
}
