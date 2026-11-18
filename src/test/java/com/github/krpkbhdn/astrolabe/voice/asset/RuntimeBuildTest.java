package com.github.krpkbhdn.astrolabe.voice.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeBuildTest {

    @TempDir
    Path directory;

    @Test
    void libraryDirectoryIsTheReleaseFolderOfTheArchive() {
        assertThat(RuntimeBuild.WINDOWS_X64_CPU.libraryDirectory(directory)).isEqualTo(directory.resolve("Release"));
    }

    @Test
    void platformMatchingNormalizesJvmOsAndArchNames() {
        assertThat(RuntimeBuild.forPlatform("Windows 11", "amd64")).containsExactly(RuntimeBuild.WINDOWS_X64_CPU, RuntimeBuild.WINDOWS_X64_CUDA12);
        assertThat(RuntimeBuild.forPlatform("Linux", "amd64")).containsExactly(RuntimeBuild.LINUX_X64_CPU);
        assertThat(RuntimeBuild.forPlatform("Linux", "x86_64")).containsExactly(RuntimeBuild.LINUX_X64_CPU);
        assertThat(RuntimeBuild.forPlatform("Linux", "aarch64")).containsExactly(RuntimeBuild.LINUX_ARM64_CPU);
        assertThat(RuntimeBuild.forPlatform("Mac OS X", "aarch64")).containsExactly(RuntimeBuild.MACOS_UNIVERSAL);
        assertThat(RuntimeBuild.forPlatform("Mac OS X", "x86_64")).containsExactly(RuntimeBuild.MACOS_UNIVERSAL);
    }

    @Test
    void unsupportedPlatformsHaveNoBuild() {
        assertThat(RuntimeBuild.forPlatform("FreeBSD", "amd64")).isEmpty();
        assertThat(RuntimeBuild.forPlatform("Windows 11", "aarch64")).isEmpty();
        assertThat(RuntimeBuild.forPlatform("Linux", "riscv64")).isEmpty();
    }

    @Test
    void everyBuildPinsAnHttpsUrlAndASha256() {
        for (var build : RuntimeBuild.values()) {
            assertThat(build.asset().url()).startsWith("https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/");
            assertThat(build.asset().sha256()).matches("[0-9a-f]{64}");
            assertThat(build.asset().sizeBytes()).isPositive();
        }
    }

    @Test
    void linuxAndMacLibrariesSitInTheInstallDirectory() {
        assertThat(RuntimeBuild.LINUX_X64_CPU.libraryDirectory(directory)).isEqualTo(directory);
        assertThat(RuntimeBuild.LINUX_X64_CPU.libraries()).containsExactly("libggml-base.so.0.18.1", "libggml.so.0.18.1", "libwhisper.so.1.9.2");
        assertThat(RuntimeBuild.MACOS_UNIVERSAL.libraryDirectory(directory)).isEqualTo(directory);
        assertThat(RuntimeBuild.MACOS_UNIVERSAL.libraries()).containsExactly("whisper");
        assertThat(RuntimeBuild.MACOS_UNIVERSAL.asset().archiveEntry()).endsWith("/Versions/A/whisper");
    }

    @Test
    void cpuBuildLoadsGgmlBeforeWhisper() {
        assertThat(RuntimeBuild.WINDOWS_X64_CPU.libraries()).containsExactly("ggml-base.dll", "ggml.dll", "whisper.dll");
    }

    @Test
    void cudaBuildLoadsItsBundledRuntimeFirst() {
        assertThat(RuntimeBuild.WINDOWS_X64_CUDA12.libraries()).containsExactly("cudart64_12.dll", "cublasLt64_12.dll",
                "cublas64_12.dll", "ggml-base.dll", "ggml.dll", "whisper.dll");
    }

    @Test
    void isCompleteOnlyWithEveryLibrary() throws IOException {
        for (var name : RuntimeBuild.WINDOWS_X64_CPU.libraries()) {
            assertThat(RuntimeBuild.WINDOWS_X64_CPU.isCompleteIn(directory)).isFalse();
            Files.createFile(directory.resolve(name));
        }

        assertThat(RuntimeBuild.WINDOWS_X64_CPU.isCompleteIn(directory)).isTrue();
        assertThat(RuntimeBuild.WINDOWS_X64_CUDA12.isCompleteIn(directory)).isFalse();
    }

    @Test
    void linuxAlsoNeedsACpuBackend() throws IOException {
        for (var name : RuntimeBuild.LINUX_X64_CPU.libraries()) {
            Files.createFile(directory.resolve(name));
        }
        assertThat(RuntimeBuild.LINUX_X64_CPU.isCompleteIn(directory)).isFalse();

        Files.createFile(directory.resolve("libggml-cpu-haswell.so"));

        assertThat(RuntimeBuild.LINUX_X64_CPU.isCompleteIn(directory)).isTrue();
    }

    @Test
    void cudaBuildAlsoNeedsItsBackend() throws IOException {
        for (var name : RuntimeBuild.WINDOWS_X64_CUDA12.libraries()) {
            Files.createFile(directory.resolve(name));
        }
        assertThat(RuntimeBuild.WINDOWS_X64_CUDA12.isCompleteIn(directory)).isFalse();

        Files.createFile(directory.resolve("ggml-cuda.dll"));

        assertThat(RuntimeBuild.WINDOWS_X64_CUDA12.isCompleteIn(directory)).isTrue();
    }
}
