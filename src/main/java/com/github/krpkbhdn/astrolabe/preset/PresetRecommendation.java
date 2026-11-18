package com.github.krpkbhdn.astrolabe.preset;

import com.github.krpkbhdn.astrolabe.toolchain.TechnologyDetector;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Picks the most specific bundled layers whose match traits a project shows; the layer with no match is always included. */
@Service
public class PresetRecommendation {

    static final Map<String, List<String>> TRAIT_MARKERS = Map.of(
            "angular", List.of("angular.json", "package.json#@angular/core"),
            "frontend", List.of("angular.json", "package.json#react", "package.json#react-dom", "package.json#vue",
                    "package.json#svelte", "package.json#next", "package.json#nuxt", "package.json#vite",
                    "package.json#@angular/core"));

    private final TechnologyDetector detector;

    public PresetRecommendation(TechnologyDetector detector) {
        this.detector = detector;
    }

    /** Blocking: scans the folder, call it off the UI thread. */
    public List<String> recommendFor(Path projectDir, List<BundledPreset> presets) {
        return recommend(detector.detectMarked(projectDir, TRAIT_MARKERS), presets);
    }

    public static List<String> recommend(Set<String> detectedTraits, List<BundledPreset> presets) {
        var matching = presets.stream().filter(preset -> detectedTraits.containsAll(preset.match())).toList();
        return matching.stream()
                .filter(preset -> preset.match().isEmpty() || matching.stream().noneMatch(other -> isStrictSubset(preset, other)))
                .map(BundledPreset::key)
                .toList();
    }

    private static boolean isStrictSubset(BundledPreset preset, BundledPreset other) {
        return other.match().size() > preset.match().size() && other.match().containsAll(preset.match());
    }
}
