package com.github.krpkbhdn.astrolabe.voice.device;

import com.github.krpkbhdn.astrolabe.voice.asset.RuntimeBuild.Flavor;

/** A processor whisper can run on; {@code gpuIndex} is -1 for the CPU. */
public record ComputeDevice(String id, String label, Kind kind, int gpuIndex, Flavor flavor) {

    public enum Kind { CPU, GPU }

    public static final ComputeDevice CPU = new ComputeDevice("cpu", "CPU", Kind.CPU, -1, Flavor.CPU);

    public static ComputeDevice nvidia(int index, String name) {
        return new ComputeDevice("gpu" + index, name, Kind.GPU, index, Flavor.CUDA);
    }

    /** The Metal GPU of the macOS runtime, which is the CPU build with Metal linked in. */
    public static ComputeDevice metal() {
        return new ComputeDevice("gpu0", "Apple GPU (Metal)", Kind.GPU, 0, Flavor.CPU);
    }
}
