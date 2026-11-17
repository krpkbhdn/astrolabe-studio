package com.github.krpkbhdn.astrolabe.ui.settings

import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend

/** What detection found for one container runtime; [imagePresent] is null while the runtime is unusable. */
data class RuntimeStatus(val available: Boolean, val reason: String, val imagePresent: Boolean?)

/** Pure wording of the Code Mode isolation page. */
object IsolationView {
    fun label(backend: IsolationBackend): String = when (backend) {
        IsolationBackend.GRAALJS -> "GraalJS (recommended)"
        IsolationBackend.DOCKER -> "Docker"
        IsolationBackend.PODMAN -> "Podman"
    }

    fun description(backend: IsolationBackend): String = when (backend) {
        IsolationBackend.GRAALJS ->
            "Runs inside the app, no extra software. There is no memory cap, so a runaway script can use app memory; the container options isolate better."
        IsolationBackend.DOCKER -> "Runs each script in a Docker container. Needs Docker installed and its daemon running."
        IsolationBackend.PODMAN -> "Runs each script in a Podman container. Needs Podman installed and its machine started."
    }

    fun isContainer(backend: IsolationBackend): Boolean = backend != IsolationBackend.GRAALJS

    /** One line for the detection result; null status means detection has not finished yet. */
    fun detection(status: RuntimeStatus?): String = when {
        status == null -> "Checking..."
        status.available -> "Installed and running"
        else -> status.reason.ifBlank { "Not available" }
    }

    fun image(status: RuntimeStatus?, image: String): String? = when (status?.imagePresent) {
        null -> null
        true -> "Image $image is present"
        false -> "Image $image is not present. It is never pulled automatically."
    }

    /** Shown when the chosen backend cannot run scripts right now. */
    fun warning(backend: IsolationBackend, status: RuntimeStatus?): String? =
        if (isContainer(backend) && status != null && (!status.available || status.imagePresent == false)) {
            "Scripts will fail until it is available; there is no silent fallback to GraalJS."
        } else {
            null
        }

    fun canPull(status: RuntimeStatus?, pulling: Boolean): Boolean = status?.available == true && status.imagePresent == false && !pulling
}
