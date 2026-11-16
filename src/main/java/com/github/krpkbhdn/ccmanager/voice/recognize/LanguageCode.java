package com.github.krpkbhdn.ccmanager.voice.recognize;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The language codes whisper.cpp v1.9.2 knows (g_lang in whisper.cpp) plus {@code auto}; an unknown one makes whisper_lang_id return -1. */
public final class LanguageCode {

    public static final String AUTO = "auto";

    private static final Set<String> KNOWN = Set.of(AUTO,
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi",
            "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la",
            "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy",
            "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be",
            "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue");

    public static final int MAX_CANDIDATES = 4;

    private LanguageCode() {}

    /** True for the stored form only: {@code auto}, one code or 2+ distinct codes joined by commas without spaces. */
    public static boolean isKnown(String language) {
        return language != null && normalize(language).filter(language::equals).isPresent();
    }

    /** Parses a typed spec (trimmed, lowercase, duplicates collapsed) into its stored form; empty when invalid. */
    public static Optional<String> normalize(String spec) {
        if (spec == null) {
            return Optional.empty();
        }
        var codes = new LinkedHashSet<String>();
        for (var part : spec.split(",", -1)) {
            codes.add(part.trim().toLowerCase(Locale.ROOT));
        }
        boolean valid = codes.size() <= MAX_CANDIDATES && KNOWN.containsAll(codes)
                && (codes.size() == 1 || !codes.contains(AUTO));
        return valid ? Optional.of(String.join(",", codes)) : Optional.empty();
    }

    /** The codes of a stored spec; more than one means whisper must choose among them. */
    public static List<String> candidates(String language) {
        return List.of(language.split(","));
    }

    /** @throws IllegalArgumentException when {@code language} is not {@code auto}, a code whisper knows or a list of them */
    public static String validate(String language) {
        if (!isKnown(language)) {
            throw new IllegalArgumentException("Unsupported language \"" + language
                    + "\"; use \"auto\", a whisper code such as en, uk, de, pl, fr or a list such as uk,en");
        }
        return language;
    }
}
