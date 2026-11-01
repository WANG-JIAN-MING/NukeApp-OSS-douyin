package io.github.nuke.oss.app.shared.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

// Only the languages this app ships strings for; SYSTEM follows the device.
enum class LanguageChoice { SYSTEM, ENGLISH, CHINESE }

// The appearance the user picked. The accent is a #RRGGBB string, null for the default; recent custom accents are
// kept newest first. A background revision of 0 means no image; otherwise it is the stored image's timestamp, so a
// replaced image reloads.
data class UiConfig(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val language: LanguageChoice = LanguageChoice.SYSTEM,
    val accent: String? = null,
    val recentAccents: List<String> = emptyList(),
    val clickHaptic: Boolean = false,
    val backgroundRevision: Long = 0,
    val backgroundBlur: Float = 0f,
    val backgroundScrim: Float = 0f,
    val cardOpacity: Float = 1f,
    val cardBlur: Boolean = false,
) {
    val hasBackground: Boolean get() = backgroundRevision != 0L
}

// Each process keeps its own copy: the module app in its preferences, a host session in the host's private directory.
interface UiConfigStore {
    // Where the picked home background is kept, as a private copy of the chosen image.
    val backgroundFile: File
    fun read(): UiConfig
    fun write(config: UiConfig)
}

// Reads and writes the settings as flat primitives so any key/value store can back them.
interface UiConfigValues {
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String): String?
    fun put(key: String, value: Any?)
}

internal class ValuesUiConfigStore(private val values: UiConfigValues, override val backgroundFile: File) : UiConfigStore {
    override fun read() = UiConfig(
        theme = ThemeChoice.entries.getOrElse(values.getInt("ui.theme", 0)) { ThemeChoice.SYSTEM },
        language = LanguageChoice.entries.getOrElse(values.getInt("ui.language", 0)) { LanguageChoice.SYSTEM },
        accent = values.getString("ui.accent")?.let(::normalizeHex),
        recentAccents = values.getString("ui.recent_accents").orEmpty().split(',').mapNotNull(::normalizeHex),
        clickHaptic = values.getBoolean("ui.click_haptic", false),
        // A revision without its file would show an empty background, so it reads as none.
        backgroundRevision = values.getLong("ui.background", 0).takeIf { backgroundFile.isFile } ?: 0,
        backgroundBlur = values.getFloat("ui.background_blur", 0f).coerceIn(0f, 40f),
        backgroundScrim = values.getFloat("ui.background_scrim", 0f).coerceIn(0f, 1f),
        cardOpacity = values.getFloat("ui.card_opacity", 1f).coerceIn(0.2f, 1f),
        cardBlur = values.getBoolean("ui.card_blur", false),
    )

    override fun write(config: UiConfig) {
        values.put("ui.theme", config.theme.ordinal)
        values.put("ui.language", config.language.ordinal)
        values.put("ui.accent", config.accent)
        values.put("ui.recent_accents", config.recentAccents.joinToString(","))
        values.put("ui.click_haptic", config.clickHaptic)
        values.put("ui.background", config.backgroundRevision)
        values.put("ui.background_blur", config.backgroundBlur)
        values.put("ui.background_scrim", config.backgroundScrim)
        values.put("ui.card_opacity", config.cardOpacity)
        values.put("ui.card_blur", config.cardBlur)
    }
}

internal fun preferencesUiConfigStore(context: Context): UiConfigStore {
    val preferences = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val values = object : UiConfigValues {
        override fun getInt(key: String, default: Int) = preferences.getInt(key, default)
        override fun getLong(key: String, default: Long) = preferences.getLong(key, default)
        override fun getFloat(key: String, default: Float) = preferences.getFloat(key, default)
        override fun getBoolean(key: String, default: Boolean) = preferences.getBoolean(key, default)
        override fun getString(key: String) = preferences.getString(key, null)
        override fun put(key: String, value: Any?) {
            preferences.edit().apply {
                when (value) {
                    null -> remove(key)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> error("Unsupported preference type ${value.javaClass.name}")
                }
            }.apply()
        }
    }
    return ValuesUiConfigStore(values, File(context.filesDir, "ui/home-background"))
}

// A picked image is copied into the store's private file, so the grant on the chosen document is not needed later.
internal object BackgroundImages {
    private const val MAX_BYTES = 32L * 1024 * 1024

    // Returns the revision of the stored copy, or fails without leaving a partial file behind.
    fun import(resolver: ContentResolver, uri: Uri, target: File): Long {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        try {
            val source = resolver.openInputStream(uri) ?: throw IOException("Cannot open the chosen image")
            source.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) throw IOException("The chosen image is larger than 32 MiB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Cannot store the chosen image")
            // Same-second replacements still get a new revision.
            return maxOf(target.lastModified(), System.currentTimeMillis())
        } finally {
            partial.delete()
        }
    }

    fun remove(target: File) {
        target.delete()
    }
}

// #RRGGBB, upper case, or null when the text is not a six digit hex colour.
fun normalizeHex(text: String): String? {
    val value = text.trim().removePrefix("#")
    if (value.length != 6 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return "#${value.uppercase()}"
}

private const val ACCENT_HISTORY_LIMIT = 12

// Newest first, without duplicates, the default and the presets (those are always on offer anyway).
fun List<String>.withAccentHistory(hex: String, presets: Set<String>): List<String> {
    val normalized = normalizeHex(hex) ?: return this
    if (normalized in presets) return this
    return (listOf(normalized) + this).distinct().take(ACCENT_HISTORY_LIMIT)
}

// What both settings screens do with the appearance: store changes, and take or drop the home background.
internal class UiAppearance(private val store: UiConfigStore) {
    val backgroundFile: File get() = store.backgroundFile

    fun read(): UiConfig = store.read()

    fun write(config: UiConfig) = store.write(config)

    // Null when the image could not be read or stored; the previous background is then left as it was.
    suspend fun importBackground(resolver: ContentResolver, uri: Uri, current: UiConfig): UiConfig? =
        withContext(Dispatchers.IO) {
            try {
                val revision = BackgroundImages.import(resolver, uri, store.backgroundFile)
                current.copy(backgroundRevision = revision).also(store::write)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    fun clearBackground(current: UiConfig): UiConfig {
        BackgroundImages.remove(store.backgroundFile)
        return current.copy(backgroundRevision = 0).also(store::write)
    }
}
