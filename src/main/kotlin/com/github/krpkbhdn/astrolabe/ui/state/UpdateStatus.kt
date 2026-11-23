package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.update.AvailableUpdate
import java.nio.file.Path

/** Where the self-update stands: nothing new, offered, being downloaded, or downloaded and waiting for the install (Preparing while the installer is staged). */
sealed interface UpdateStatus {
    data object None : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(val update: AvailableUpdate, val fraction: Float?) : UpdateStatus
    data class Preparing(val update: AvailableUpdate) : UpdateStatus
    /** The install was confirmed: the script is started and the app is closing. */
    data class Installing(val update: AvailableUpdate) : UpdateStatus
    data class Ready(val update: AvailableUpdate, val installer: Path) : UpdateStatus
}
