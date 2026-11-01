package io.github.nuke.oss.app.tiktok.feature.playback

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

// Draws a danmaku in a random light color, white one time in four. Colors are
// opaque and never pure white, and their luma is at least 150 so the text stays readable on video.
internal fun lightDanmakuColor(random: Random): Int {
    if (random.nextInt(100) < 25) return WHITE
    while (true) {
        val red = random.nextInt(256)
        val green = random.nextInt(256)
        val blue = random.nextInt(256)
        if (red == 255 && green == 255 && blue == 255) continue
        if (red * 0.299 + green * 0.587 + blue * 0.114 >= 150.0) return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

private const val WHITE = -1
private const val MARKER = 0x5A5A5A5A
private val TEXT_COLOR = Regex("textColor=(-?\\d+)")

private fun printedTextColor(style: Any): Int? = TEXT_COLOR.find(style.toString())?.groupValues?.get(1)?.toIntOrNull()

// The one int field whose value the style's own text shows as its text color, or null when no field or several do.
private fun findTextColorField(style: Any, fields: List<Field>): Field? = fields.filter { field ->
    val original = field.getInt(style)
    val marker = original xor MARKER
    field.setInt(style, marker)
    try {
        printedTextColor(style) == marker
    } finally {
        field.setInt(style, original)
    }
}.singleOrNull()

// The text color is the style config's second constructor argument. The Hook model only observes a
// constructor after it succeeds, so the color is written into the field that holds it instead. The config prints
// itself with a textColor=<int> entry, so that field is found once, on the first style, by giving each int field a marker
// value in turn and seeing which one the printed text color follows. Comparing the constructor argument with the fields
// would find nothing useful when two fields hold the same value, or when the constructor stores the color altered.
@HookEntry
internal class ColorfulDanmaku(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    private val random: Random = Random.Default,
) : RuntimeFeature, UsesDexQueries {
    override val id = "ColorfulDanmaku"
    override val dexQueries = listOf(FeedQueries.DANMAKU_STYLE)

    override fun install(scope: FeatureScope) {
        val config = resolver.methods(hostClassLoader, FeedQueries.DANMAKU_STYLE).single().declaringClass
        val int = Int::class.javaPrimitiveType
        val constructors = config.declaredConstructors.filter { !it.isSynthetic && it.parameterCount >= 2 && it.parameterTypes[1] == int }
        check(constructors.isNotEmpty()) { "${config.name} has no constructor taking the text color second" }
        val fields = config.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == int }.onEach { it.isAccessible = true }
        check(fields.isNotEmpty()) { "${config.name} has no int field" }
        val colorField = AtomicReference<Field?>()
        val lost = AtomicBoolean()
        val written = AtomicBoolean()
        val noReceiver = AtomicBoolean()
        val fired = AtomicBoolean()
        logger.log("feature=$id installed config=${config.name} constructors=${constructors.size} intFields=${fields.size}")

        fun resolveField(style: Any): Field? {
            colorField.get()?.let { return it }
            if (lost.get()) return null
            val found = findTextColorField(style, fields)
            if (found == null) {
                if (lost.compareAndSet(false, true)) logger.log("feature=$id no_color_field intFields=${fields.size}")
                return null
            }
            if (colorField.compareAndSet(null, found)) logger.log("feature=$id color_field=${found.name}")
            return colorField.get()
        }

        constructors.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            scope.hooks.install(constructor, HookSpec(
                id = "Color$index",
                requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
                after = after@{ call ->
                    val style = call.receiver
                    if (fired.compareAndSet(false, true)) {
                        logger.log("feature=$id after_fired outcome=${call.outcome.javaClass.simpleName} receiver=${style?.javaClass?.name}")
                    }
                    if (style == null && noReceiver.compareAndSet(false, true)) logger.log("feature=$id style_seen without a receiver")
                    if (call.outcome !is CallOutcome.Returned || style == null || style.javaClass != config) return@after
                    val field = resolveField(style) ?: return@after
                    field.setInt(style, lightDanmakuColor(random))
                    if (written.compareAndSet(false, true)) logger.log("feature=$id color_written printed=${printedTextColor(style)}")
                },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) { listOf(ColorfulDanmaku(resolver, hostClassLoader, logger)) }
    }
}
