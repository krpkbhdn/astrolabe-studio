package com.github.krpkbhdn.ccmanager.ui.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.krpkbhdn.ccmanager.extension.ExtensionScope
import com.github.krpkbhdn.ccmanager.ui.components.Chip
import com.github.krpkbhdn.ccmanager.ui.theme.colors

fun ExtensionScope.label(): String = when (this) {
    ExtensionScope.USER -> "User"
    ExtensionScope.PROJECT -> "In repository"
    ExtensionScope.APP -> "Project"
    ExtensionScope.LOCAL -> "Local"
    ExtensionScope.PLUGIN -> "Plugin"
}

fun ExtensionScope.description(): String = when (this) {
    ExtensionScope.USER -> "Every project of yours"
    ExtensionScope.PROJECT -> "Kept in the repository, shared with the team through git"
    ExtensionScope.APP -> "Only this project, kept by the app outside the repository"
    ExtensionScope.LOCAL -> "Only you, only in this project"
    ExtensionScope.PLUGIN -> "Provided by an installed plugin"
}

/** Wording for choosing where to install. */
fun ExtensionScope.choiceLabel(): String = when (this) {
    ExtensionScope.USER -> "For you, in every project"
    ExtensionScope.PROJECT -> "This project, shared through git"
    ExtensionScope.APP -> "This project, kept by the app"
    ExtensionScope.LOCAL -> "This project, only you"
    ExtensionScope.PLUGIN -> "Provided by a plugin"
}

@Composable
private fun ExtensionScope.tint(): Color = when (this) {
    ExtensionScope.USER -> colors.textSecondary
    ExtensionScope.PROJECT -> colors.accent
    ExtensionScope.APP -> colors.accent
    ExtensionScope.LOCAL -> colors.warning
    ExtensionScope.PLUGIN -> colors.textSecondary
}

@Composable
fun ScopeBadge(scope: ExtensionScope, modifier: Modifier = Modifier) = Chip(scope.label(), scope.tint(), modifier)
