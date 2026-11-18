package com.github.krpkbhdn.astrolabe.voice.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** whisper.cpp release archives with the ggml and whisper libraries loaded in-process, pinned to one release tag. */
public enum RuntimeBuild {
    WINDOWS_X64_CPU(Os.WINDOWS, Arch.X64, Flavor.CPU, "whisper-bin-x64.zip",
            "49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a", 8194445L, "Release", "",
            "ggml-base.dll", "ggml.dll", "whisper.dll"),
    WINDOWS_X64_CUDA12(Os.WINDOWS, Arch.X64, Flavor.CUDA, "whisper-cublas-12.4.0-bin-x64.zip",
            "443110ddaad70d4290ab2e77179e31cf712035bbc4fad56bb4519a90c917b39c", 670611449L, "Release", "",
            "ggml-base.dll", "ggml.dll", "whisper.dll"),
    // Real file names: their sonames (libggml-base.so.0, ...) match the NEEDED entries of the libraries loaded later.
    LINUX_X64_CPU(Os.LINUX, Arch.X64, Flavor.CPU, "whisper-bin-ubuntu-x64.tar.gz",
            "46811a3ecf584307480a220b9ef5ff81b7b22dc41577cbc274ce3afc61f753b1", 9497583L, "", "",
            "libggml-base.so.0.18.1", "libggml.so.0.18.1", "libwhisper.so.1.9.2"),
    LINUX_ARM64_CPU(Os.LINUX, Arch.ARM64, Flavor.CPU, "whisper-bin-ubuntu-arm64.tar.gz",
            "7e26fa6a36d9174d5c0bf033ccbc026c3b5e569e2ee787058241346ef5392719", 4572842L, "", "",
            "libggml-base.so.0.18.1", "libggml.so.0.18.1", "libwhisper.so.1.9.2"),
    // One self-contained universal dylib (ggml, Metal and Accelerate linked in); only this entry is unpacked.
    MACOS_UNIVERSAL(Os.MAC, Arch.UNIVERSAL, Flavor.CPU, "whisper-v1.9.2-xcframework.zip",
            "af74fed13ea7f2d5ca2a39d9f58ec177713fafd7cab63aef4e27b79f3ceca80b", 53575921L, "",
            "build-apple/whisper.xcframework/macos-arm64_x86_64/whisper.framework/Versions/A/whisper", "whisper");

    public enum Flavor { CPU, CUDA }

    enum Os { WINDOWS, LINUX, MAC }

    enum Arch { X64, ARM64, UNIVERSAL }

    private static final String RELEASE_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/";
    private static final List<String> CUDA_RUNTIME_LIBRARIES = List.of("cudart64_12.dll", "cublasLt64_12.dll", "cublas64_12.dll");
    private static final String LINUX_CPU_BACKEND_PREFIX = "libggml-cpu";
    private static final String CUDA_BACKEND = "ggml-cuda.dll";

    private final Os os;
    private final Arch arch;
    private final Flavor flavor;
    private final String libraryDirectory;
    private final List<String> coreLibraries;
    private final VoiceAsset asset;

    RuntimeBuild(Os os, Arch arch, Flavor flavor, String archive, String sha256, long sizeBytes,
            String libraryDirectory, String archiveEntry, String... coreLibraries) {
        this.os = os;
        this.arch = arch;
        this.flavor = flavor;
        this.libraryDirectory = libraryDirectory;
        this.coreLibraries = List.of(coreLibraries);
        this.asset = new VoiceAsset("whisper.cpp " + flavor, RELEASE_URL + archive, sha256, sizeBytes,
                "runtime/" + name().toLowerCase(Locale.ROOT), true, archiveEntry);
    }

    public Flavor flavor() {
        return flavor;
    }

    public VoiceAsset asset() {
        return asset;
    }

    /** The directory of the unpacked archive that holds the libraries, given the install directory of {@link #asset()}. */
    public Path libraryDirectory(Path installed) {
        return installed.resolve(libraryDirectory);
    }

    /**
     * The libraries to load by full path, dependencies first. The CUDA runtime is bundled and loaded here because
     * ggml-cuda.dll looks for it on PATH only; the backends themselves (ggml-cpu-*, ggml-cuda.dll) are loaded by ggml.
     */
    public List<String> libraries() {
        return flavor == Flavor.CUDA
                ? Stream.concat(CUDA_RUNTIME_LIBRARIES.stream(), coreLibraries.stream()).toList()
                : coreLibraries;
    }

    /** Whether every library of this build is in {@code libraryDirectory}; Linux also needs one CPU backend, without it whisper cannot start. */
    public boolean isCompleteIn(Path libraryDirectory) {
        return Stream.concat(libraries().stream(), flavor == Flavor.CUDA ? Stream.of(CUDA_BACKEND) : Stream.<String>empty())
                .allMatch(name -> Files.isRegularFile(libraryDirectory.resolve(name)))
                && (os != Os.LINUX || hasCpuBackend(libraryDirectory));
    }

    private static boolean hasCpuBackend(Path libraryDirectory) {
        try (Stream<Path> files = Files.list(libraryDirectory)) {
            return files.map(file -> file.getFileName().toString())
                    .anyMatch(name -> name.startsWith(LINUX_CPU_BACKEND_PREFIX) && name.endsWith(".so"));
        } catch (IOException e) {
            return false;
        }
    }

    public static List<RuntimeBuild> forCurrentPlatform() {
        return forPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Builds for the JVM's {@code os.name} and {@code os.arch} strings; empty for an unsupported combination. */
    public static List<RuntimeBuild> forPlatform(String osName, String osArch) {
        var currentOs = osOf(osName.toLowerCase(Locale.ROOT));
        var currentArch = archOf(osArch.toLowerCase(Locale.ROOT));
        if (currentOs == null || currentArch == null) {
            return List.of();
        }
        return Arrays.stream(values())
                .filter(build -> build.os == currentOs && (build.arch == Arch.UNIVERSAL || build.arch == currentArch))
                .toList();
    }

    private static Os osOf(String name) {
        if (name.contains("windows")) {
            return Os.WINDOWS;
        }
        if (name.contains("linux")) {
            return Os.LINUX;
        }
        return name.contains("mac") || name.contains("darwin") ? Os.MAC : null;
    }

    private static Arch archOf(String name) {
        return switch (name) {
            case "amd64", "x86_64", "x64" -> Arch.X64;
            case "aarch64", "arm64" -> Arch.ARM64;
            default -> null;
        };
    }
}
