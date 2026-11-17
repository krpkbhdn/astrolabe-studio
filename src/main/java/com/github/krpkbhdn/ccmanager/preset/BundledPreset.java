package com.github.krpkbhdn.ccmanager.preset;

import java.util.List;

/** @param match technology traits for which this layer is recommended; components are already flattened over {@code includes} */
public record BundledPreset(String key, int version, String name, String description, List<PresetComponentSpec> components, List<String> match) {

    public BundledPreset(String key, int version, String name, String description, List<PresetComponentSpec> components) {
        this(key, version, name, description, components, List.of());
    }
}
