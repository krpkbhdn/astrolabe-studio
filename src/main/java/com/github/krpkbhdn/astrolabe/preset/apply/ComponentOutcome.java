package com.github.krpkbhdn.astrolabe.preset.apply;

import com.github.krpkbhdn.astrolabe.preset.PresetComponent;
import com.github.krpkbhdn.astrolabe.preset.PresetComponentKind;

public record ComponentOutcome(long componentId, PresetComponentKind kind, String title, OutcomeStatus status, String message) {

    static ComponentOutcome of(PresetComponent component, OutcomeStatus status, String message) {
        return new ComponentOutcome(component.id(), component.kind(), component.name(), status, message);
    }
}
