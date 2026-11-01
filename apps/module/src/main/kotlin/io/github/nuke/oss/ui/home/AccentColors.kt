package io.github.nuke.oss.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.nuke.oss.app.shared.settings.normalizeHex
import io.github.nuke.oss.designsystem.NukeAccents

internal fun Color.toHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)

internal fun String?.toAccentColor(): Color? {
    val hex = this?.let(::normalizeHex) ?: return null
    return Color(0xFF000000 or hex.drop(1).toLong(16))
}

// The default first, then the other presets; these are always on offer, so only other colours enter the history.
internal val DefaultAccentHex: String = NukeAccents.SakuraPink.toHex()
internal val PresetAccentHexes: List<String> = NukeAccents.Presets.map { it.toHex() }.distinct()
