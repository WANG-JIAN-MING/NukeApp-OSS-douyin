package io.github.nuke.oss.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.app.shared.settings.LanguageChoice
import io.github.nuke.oss.app.shared.settings.ThemeChoice
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.designsystem.NukeAccents
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.NukeThemeMode
import java.util.Locale

private fun LanguageChoice.locale(): Locale? = when (this) {
    LanguageChoice.SYSTEM -> null
    LanguageChoice.ENGLISH -> Locale.ENGLISH
    LanguageChoice.CHINESE -> Locale.SIMPLIFIED_CHINESE
}

// The module's own package context in the chosen language. Built from the package, not from the Activity, because
// inside a host process the Activity's base context is the host's.
private fun localizedContext(base: Context, locale: Locale): Context {
    val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
    return try {
        base.createPackageContext(BuildConfig.APPLICATION_ID, 0).createConfigurationContext(configuration)
    } catch (_: PackageManager.NameNotFoundException) {
        base
    }
}

// Applies the stored appearance to everything below it: colours, click haptics and the language of every string.
@Composable
internal fun NukeThemed(ui: UiConfig, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val locale = ui.language.locale()
    val context = remember(base, systemConfiguration, locale) { if (locale == null) base else localizedContext(base, locale) }
    val configuration = remember(context) { Configuration(context.resources.configuration) }
    // A configuration Context is not the Activity, so what the Activity owns has to be handed down explicitly.
    val provided = buildList<ProvidedValue<*>> {
        add(LocalContext provides context)
        add(LocalConfiguration provides configuration)
        (base as? ActivityResultRegistryOwner)?.let { add(LocalActivityResultRegistryOwner provides it) }
        (base as? OnBackPressedDispatcherOwner)?.let { add(LocalOnBackPressedDispatcherOwner provides it) }
    }
    CompositionLocalProvider(*provided.toTypedArray()) {
        NukeTheme(
            mode = when (ui.theme) {
                ThemeChoice.SYSTEM -> NukeThemeMode.System
                ThemeChoice.LIGHT -> NukeThemeMode.Light
                ThemeChoice.DARK -> NukeThemeMode.Dark
            },
            accent = ui.accent.toAccentColor() ?: NukeAccents.SakuraPink,
            clickHaptic = ui.clickHaptic,
            content = content,
        )
    }
}
