package com.github.krpkbhdn.ccmanager.preset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the bundled presets from the classpath; a broken file is a build defect, so it fails fast with the resource named.
 * Coupling: the General preset's skill sources point into {@code author-profile/user/skills}, so those folders must stay on the classpath.
 */
@Component
public class BundledPresetLoader {

    private static final String RESOURCE = "presets/presets.json";
    private static final JsonMapper MAPPER = PresetComponentValidator.MAPPER;

    private record Document(List<RawPreset> presets) {
    }

    private record RawPreset(String key, int version, String name, String description, List<RawComponent> components,
                             List<String> includes, List<String> match) {
    }

    /** @param source classpath file (INSTRUCTION markdown, FILE content) or folder (SKILL files) holding the text of the component */
    private record RawComponent(PresetComponentKind kind, String name, String source, JsonNode payload) {
    }

    private final List<BundledPreset> presets;

    public BundledPresetLoader() {
        this(RESOURCE);
    }

    BundledPresetLoader(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            this.presets = flatten(MAPPER.readValue(in, Document.class).presets());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Bundled presets cannot be loaded from " + resource + ": " + e.getMessage(), e);
        }
    }

    BundledPresetLoader(List<BundledPreset> presets) {
        this.presets = List.copyOf(presets);
    }

    public List<BundledPreset> presets() {
        return presets;
    }

    private static List<BundledPreset> flatten(List<RawPreset> raws) {
        var byKey = new LinkedHashMap<String, RawPreset>();
        for (var raw : raws) {
            if (byKey.put(raw.key(), raw) != null) {
                throw new IllegalStateException("Bundled preset key is used twice: " + raw.key());
            }
        }
        return raws.stream().map(raw -> {
            var components = new LinkedHashMap<String, PresetComponentSpec>();
            collect(raw, byKey, new ArrayList<>(), components);
            var match = raw.match() == null ? List.<String>of() : raw.match();
            if (match.stream().anyMatch(trait -> trait == null || trait.isBlank())) {
                throw new IllegalStateException("Bundled preset '" + raw.key() + "' has a blank match trait");
            }
            return new BundledPreset(raw.key(), raw.version(), raw.name(), raw.description(), List.copyOf(components.values()), List.copyOf(match));
        }).toList();
    }

    /** Included presets first (first occurrence wins among them), then the preset's own components override by kind and name. */
    private static void collect(RawPreset raw, Map<String, RawPreset> byKey, List<String> path, Map<String, PresetComponentSpec> out) {
        if (path.contains(raw.key())) {
            throw new IllegalStateException("Bundled presets include each other in a cycle: " + String.join(" -> ", path) + " -> " + raw.key());
        }
        path.add(raw.key());
        for (var key : raw.includes() == null ? List.<String>of() : raw.includes()) {
            var included = byKey.get(key);
            if (included == null) {
                throw new IllegalStateException("Bundled preset '" + raw.key() + "' includes unknown preset '" + key + "'");
            }
            var inherited = new LinkedHashMap<String, PresetComponentSpec>();
            collect(included, byKey, path, inherited);
            inherited.forEach(out::putIfAbsent);
        }
        path.removeLast();
        for (var component : raw.components() == null ? List.<RawComponent>of() : raw.components()) {
            var spec = resolve(component);
            out.put(spec.kind() + ":" + spec.name(), spec);
        }
    }

    private static PresetComponentSpec resolve(RawComponent raw) {
        var payload = switch (raw.kind()) {
            case INSTRUCTION -> instruction(raw);
            case SKILL -> MAPPER.createObjectNode().set("files", MAPPER.valueToTree(folder(raw.source())));
            case FILE -> MAPPER.createObjectNode().setAll((ObjectNode) raw.payload()).put("content", text(raw.source()));
            default -> raw.payload();
        };
        return new PresetComponentSpec(raw.kind(), raw.name(), payload);
    }

    private static ObjectNode instruction(RawComponent raw) {
        var node = MAPPER.createObjectNode().put("markdown", text(raw.source()));
        if (raw.payload() != null && raw.payload().has("scope")) {
            node.set("scope", raw.payload().get("scope"));
        }
        return node;
    }

    private static String text(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled preset source is missing on the classpath: " + path, e);
        }
    }

    private static TreeMap<String, String> folder(String dir) {
        var files = new TreeMap<String, String>();
        var marker = "/" + dir + "/";
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:" + dir + "/**")) {
                var url = resource.getURL().toString();
                if (!url.endsWith("/")) {
                    var relative = url.substring(url.indexOf(marker) + marker.length());
                    files.put(relative, text(dir + "/" + relative));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled preset folder cannot be read: " + dir, e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No files in bundled preset folder " + dir);
        }
        return files;
    }
}
