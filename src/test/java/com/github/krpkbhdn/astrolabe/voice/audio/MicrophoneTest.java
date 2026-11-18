package com.github.krpkbhdn.astrolabe.voice.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import org.junit.jupiter.api.Test;

class MicrophoneTest {

    @Test
    void macOpenFailureNamesThePrivacySetting() {
        assertThat(Microphone.openHint(OperatingSystem.MACOS)).contains("System Settings > Privacy & Security > Microphone");
    }

    @Test
    void otherSystemsGetNoHint() {
        assertThat(Microphone.openHint(OperatingSystem.WINDOWS)).isEmpty();
        assertThat(Microphone.openHint(OperatingSystem.LINUX)).isEmpty();
    }
}
