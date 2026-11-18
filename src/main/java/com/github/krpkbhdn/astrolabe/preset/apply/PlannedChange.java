package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;

/**
 * One line of the apply preview.
 *
 * @param detail the reason or a short description
 * @param preview the new text to show, or null
 */
public record PlannedChange(long componentId, PresetComponentKind kind, String title, ChangeAction action, String detail, String preview) {

    static PlannedChange of(PresetComponent component, ChangeAction action, String detail) {
        return new PlannedChange(component.id(), component.kind(), component.name(), action, detail, null);
    }

    PlannedChange withDetail(String text) {
        return new PlannedChange(componentId, kind, title, action, text, preview);
    }

    PlannedChange withPreview(String text) {
        return new PlannedChange(componentId, kind, title, action, detail, text);
    }
}
