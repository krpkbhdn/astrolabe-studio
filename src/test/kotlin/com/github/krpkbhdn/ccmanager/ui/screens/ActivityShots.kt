package com.github.krpkbhdn.ccmanager.ui.screens

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.github.krpkbhdn.ccmanager.team.Team
import com.github.krpkbhdn.ccmanager.ui.state.TeamChoice
import com.github.krpkbhdn.ccmanager.ui.theme.AppTheme
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat

private const val FRAME_NANOS = 16_000_000L
private const val FRAMES = 20
private const val WIDTH = 900
private const val HEIGHT = 420

/** Renders the Activity empty state offscreen. `--out=<dir> --dark|--light`. */
fun main(args: Array<String>) {
    val out = Path.of(args.firstOrNull { it.startsWith("--out=") }?.removePrefix("--out=") ?: "build/ccm-shots/activity")
    val themes = when { "--dark" in args -> listOf(true); "--light" in args -> listOf(false); else -> listOf(true, false) }
    Files.createDirectories(out)
    val dev = Team(1, "Dev team", emptyList())
    themes.forEach { dark ->
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            AppTheme(dark) { ActivityTab(emptyList(), hasSession = false, teams = TeamChoice(listOf(dev), dev) {}, onLaunch = { _, _ -> }, launching = false) }
        }
        repeat(FRAMES) { scene.render(it * FRAME_NANOS) }
        val file = out.resolve("activity-${if (dark) "dark" else "light"}.png")
        Files.write(file, scene.render(FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${file.toAbsolutePath()}")
        scene.close()
    }
    kotlin.system.exitProcess(0)
}
