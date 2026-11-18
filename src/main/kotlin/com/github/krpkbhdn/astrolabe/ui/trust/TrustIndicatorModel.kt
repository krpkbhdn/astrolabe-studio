package com.github.krpkbhdn.astrolabe.ui.trust

import com.github.krpkbhdn.astrolabe.project.Project
import com.github.krpkbhdn.astrolabe.project.TrustLevel

enum class TrustTone { NEUTRAL, GOOD, DANGER }

/** One row of the quick popup; [level] null is "App default", [checked] marks the project's current choice. */
data class TrustOption(val level: TrustLevel?, val label: String, val checked: Boolean)

data class TrustIndicator(val label: String, val tone: TrustTone, val lines: List<String>, val options: List<TrustOption>)

const val TRUST_HINT = "Applies from the next session start."

fun trustLabel(level: TrustLevel): String = level.name.lowercase().replaceFirstChar { it.uppercase() }

fun trustShortLabel(level: TrustLevel): String = if (level == TrustLevel.AUTONOMOUS) "Auto" else trustLabel(level)

fun trustDescription(level: TrustLevel): String = when (level) {
    TrustLevel.ASK -> "Claude asks before anything beyond its defaults."
    TrustLevel.TRUSTED -> "Edits and read-only commands in the project run without asking; reads inside the project are approved for subagents too."
    TrustLevel.AUTONOMOUS -> "Every tool call is approved automatically, except the app's own astrolabe tools."
}

const val AUTONOMOUS_WARNING = "Claude runs everything without asking, even where your own settings say ask. " +
    "Common destructive commands and secret files are blocked on a best-effort basis only. Use it in projects you can restore."

fun appDefaultLabel(appDefault: TrustLevel): String = "App default (${trustLabel(appDefault)})"

fun trustTone(level: TrustLevel): TrustTone = when (level) {
    TrustLevel.ASK -> TrustTone.NEUTRAL
    TrustLevel.TRUSTED -> TrustTone.GOOD
    TrustLevel.AUTONOMOUS -> TrustTone.DANGER
}

/** The status bar chip of [project]; null for no project or a group, which always asks. */
fun trustIndicatorOf(project: Project?, appDefault: TrustLevel): TrustIndicator? {
    if (project == null || project.group) return null
    val override = project.trustOverride()
    val effective = override ?: appDefault
    val source = if (override == null) "app default" else "this project"
    val options = listOf(TrustOption(null, appDefaultLabel(appDefault), override == null)) +
        TrustLevel.entries.map { TrustOption(it, trustLabel(it), it == override) }
    return TrustIndicator(trustShortLabel(effective), trustTone(effective), listOf("Trust level: ${trustLabel(effective)} ($source)", trustDescription(effective)), options)
}
