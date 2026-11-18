package com.github.krpkbhdn.astrolabe.voice.device;

import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment;
import com.github.krpkbhdn.astrolabe.workspace.HostEnvironment.OperatingSystem;
import java.util.List;
import org.springframework.stereotype.Component;

/** Offers the Metal GPU on macOS; whether the loaded runtime really exposes it is checked when the recogniser starts. */
@Component
public class MetalProbe implements GpuProbe {

    private final HostEnvironment host;

    public MetalProbe(HostEnvironment host) {
        this.host = host;
    }

    @Override
    public List<ComputeDevice> probe() {
        return host.os() == OperatingSystem.MACOS ? List.of(ComputeDevice.metal()) : List.of();
    }
}
