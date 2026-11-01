package io.github.nuke.oss.app.tiktok.feature.feed

import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.query.ViewQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.app.tiktok.sdk.media.mediaUrls
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.ConcurrentHashMap

// The View work the long-press copy needs.
internal interface TextCopier {
    fun install(scope: FeatureScope, featureId: String)

    // Puts the text on the clipboard with a short notice where the system gives none; false for a blank text or no clipboard.
    fun copy(text: CharSequence?): Boolean

    // Lets the View take a long press, remembering how it was and putting that back when the feature closes.
    fun makeLongClickable(view: Any)

    // The long press was used: keep the parents from taking the gesture and give the usual haptic tick.
    fun confirmLongPress(view: Any)
}

// Long-press to copy: a comment (as its menu opens), a profile signature, and a video caption, each switched on in the
// options. Comments reuse the host's own text for the menu's copy entry; the signature and caption are copied when the
// press lands on them and the host's own long press is then not run.
@HookEntry
internal class LongPressCopy(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val copier: () -> TextCopier,
    private val longPressOwner: Class<*> = View::class.java,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    private val targets = options.multi(TARGETS)
    // Only what the chosen targets resolve.
    override val dexQueries: List<DexQuery> =
        (if (COMMENT in targets) listOf(ViewQueries.COMMENT_COPY_ITEM, ViewQueries.COMMENT_COPY_TEXT, ViewQueries.COMMENT_COPY_VISIBLE) else emptyList()) +
        (if (SIGNATURE in targets) listOf(ViewQueries.SIGNATURE_BIND, ViewQueries.SIGNATURE_TEXT) else emptyList()) +
        (if (CAPTION in targets) listOf(ViewQueries.CAPTION_LONG_PRESS, ViewQueries.CAPTION_AWEME, ViewQueries.CAPTION_CONTEXT) else emptyList())
    private val tempIdentifier = NamedField("tempIdentifier")
    private val uniqueIdentifier = NamedField("uniqueIdentifier")
    private val performed = NamedField("performedLongPress")

    override fun install(scope: FeatureScope) {
        if (targets.isEmpty()) return
        val ui = copier()
        ui.install(scope, ID)
        if (COMMENT in targets) {
            val (factory, text, visible) = resolver.methods(hostClassLoader, ViewQueries.COMMENT_COPY_ITEM, ViewQueries.COMMENT_COPY_TEXT, ViewQueries.COMMENT_COPY_VISIBLE)
            listOf(factory, text, visible).forEach { it.isAccessible = true }
            scope.hooks.install(factory, HookSpec(id = "Comment", after = { call ->
                val item = (call.outcome as? CallOutcome.Returned)?.value ?: return@HookSpec
                // The factory runs when a comment's long-press menu opens; its click, report and dismiss actions are not used.
                if (visible.invoke(item) == true) ui.copy(text.invoke(item) as? CharSequence)
            }))
        }
        if (SIGNATURE in targets) {
            val (bind, text) = resolver.methods(hostClassLoader, ViewQueries.SIGNATURE_BIND, ViewQueries.SIGNATURE_TEXT)
            bind.isAccessible = true
            text.isAccessible = true
            val press = longPressOwner.resolve().firstMethod { name = "performLongClick"; emptyParameters() }.self.apply { isAccessible = true }
            scope.hooks.install(bind, HookSpec(id = "SignatureBind", after = { call -> call.receiver?.let(ui::makeLongClickable) }))
            // The signature view inherits View.performLongClick, so other Views pass through untouched.
            scope.hooks.install(press, HookSpec(id = "SignaturePress", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                val view = call.receiver ?: return@HookSpec
                if (bind.declaringClass.isInstance(view) && ui.copy(text.invoke(view) as? CharSequence)) call.returnEarly(true)
            }))
        }
        if (CAPTION in targets) {
            val press = resolver.methods(hostClassLoader, ViewQueries.CAPTION_LONG_PRESS, ViewQueries.CAPTION_AWEME)
            val context = resolver.fields(hostClassLoader, ViewQueries.CAPTION_CONTEXT).single().apply { isAccessible = true }
            press.forEach { it.isAccessible = true }
            val aweme = press[1]
            scope.hooks.install(press[0], HookSpec(id = "Caption", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                val view = call.receiver ?: return@HookSpec
                // A delayed long press must still belong to the feed item now bound.
                if (tempIdentifier.of(view)?.get(view) != uniqueIdentifier.of(view)?.get(view)) return@HookSpec
                val listener = view.callByName("getLongPressListener") ?: return@HookSpec
                val host = if (context.declaringClass.isInstance(listener)) context.get(listener) else null
                val video = host?.let(aweme::invoke) ?: return@HookSpec
                val desc = video.asResolver().optional(silent = true).firstFieldOrNull { name = "desc"; superclass() }?.get()
                if (!ui.copy(desc as? CharSequence)) return@HookSpec
                performed.of(view)?.set(view, true)
                ui.confirmLongPress(view)
                call.returnEarly(null)
            }))
        }
    }

    companion object : TikTokFeature {
        const val ID = "LongPressCopy"
        const val TARGETS = "targets"
        const val COMMENT = "comment"
        const val SIGNATURE = "signature"
        const val CAPTION = "caption"
        val OPTIONS = listOf(MultiOption(TARGETS, listOf(COMMENT, SIGNATURE, CAPTION), default = setOf(COMMENT, SIGNATURE, CAPTION)))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(LongPressCopy(resolver, hostClassLoader, options(ID, OPTIONS), textCopier))
        }
    }
}

// Shows the host's own publish time and IP location label under the caption in places where it hides it. The host's
// binder writes the text, so its formatting and accessibility text stay; this only turns the IP display mode on and lets
// the label show for posts that have a usable time (not lives and ads). It is one half of VideoBottomInfo, which owns
// its option and its place in the settings.
internal class HostTimeLabel(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) {
    private val getAweme = NamedCall("getAweme")
    private val createTime = NamedField("createTime")
    private val isLive = NamedCall("isLive")
    private val getAd = NamedCall("getAd")

    fun install(scope: FeatureScope) {
        val (visibility, ipMode) = resolver.methods(hostClassLoader, ViewQueries.TIME_VISIBILITY, ViewQueries.TIME_IP_MODE)
        visibility.isAccessible = true
        ipMode.isAccessible = true
        val params = ITEM_PARAMS.toClass(hostClassLoader)
        scope.hooks.install(ipMode, HookSpec(id = "IpMode", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call -> call.returnEarly(1) }))
        scope.hooks.install(visibility, HookSpec(id = "Visibility", requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE), after = { call ->
            if ((call.outcome as? CallOutcome.Returned)?.value != GONE) return@HookSpec
            val model = call.argument(0)?.takeIf(params::isInstance) ?: return@HookSpec
            val aweme = getAweme.of(model)?.invoke(model) ?: return@HookSpec
            val created = (createTime.of(aweme)?.get(aweme) as? Number)?.toLong() ?: return@HookSpec
            // The binder leaves recycled text alone for a zero time, so only posts with a usable time are revealed.
            if (created <= 0 || created > Long.MAX_VALUE / 1000) return@HookSpec
            if (isLive.of(aweme)?.invoke(aweme) == true || getAd.of(aweme)?.invoke(aweme) == true) return@HookSpec
            call.returnValue(VISIBLE)
        }))
    }

    companion object {
        val QUERIES = listOf(ViewQueries.TIME_VISIBILITY, ViewQueries.TIME_IP_MODE)
        private const val ITEM_PARAMS = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
        private const val GONE = 8
        private const val VISIBLE = 0
    }
}

// Where the location sits inside a time label, and the colours picked for a post.
internal object LocationGradientText {
    private val palette = listOf(
        0xFFFF9FBC.toInt() to 0xFFFFD58E.toInt(),
        0xFF82E9FF.toInt() to 0xFFC7AEFF.toInt(),
        0xFFFFE28A.toInt() to 0xFFA5F1AE.toInt(),
        0xFFD2AAFF.toInt() to 0xFFFF9FDC.toInt(),
        0xFF8BF0D3.toInt() to 0xFF8FCBFF.toInt(),
        0xFFFFBE91.toInt() to 0xFFFF95A6.toInt(),
    )

    // The host's label joins the time, two spaces, an optional prefix and the raw location.
    fun regionRange(text: CharSequence, location: String?): IntRange? {
        if (location.isNullOrBlank() || location.any { it.isISOControl() } || !text.endsWith(location)) return null
        val start = text.length - location.length
        if (start < 2) return null
        val separator = text.lastIndexOf("  ", startIndex = start - 2)
        if (separator < 0 || text.subSequence(0, separator).isBlank()) return null
        return start until text.length
    }

    fun colorsFor(postId: String?, location: String, seed: Int): Pair<Int, Int> {
        val identity = postId?.takeUnless { it.isBlank() } ?: location
        return palette[(31 * identity.hashCode() + seed).mod(palette.size)]
    }
}

// The label work the location gradient needs. clear removes this feature's styling from the labels under a presenter's
// View; decorate styles the location run of the label whose accessibility text matches the host's own.
internal interface LocationLabels {
    fun clear(root: Any)
    fun decorate(root: Any, location: String, startColor: Int, endColor: Int): Boolean
    fun restoreAll()
}

// Gives the IP location in the time label a random gradient. The colours are fixed per post for the run.
@HookEntry
internal class RandomLocationGradient(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val labels: () -> LocationLabels,
    private val seed: Int = kotlin.random.Random.nextInt(),
) : RuntimeFeature, UsesDexQueries {
    override val id = "RandomLocationGradient"
    override val dexQueries = listOf(ViewQueries.TIME_BIND)
    private val getAweme = NamedCall("getAweme")
    private val createTime = NamedField("createTime")
    private val ipAttribution = NamedField("ipAttribution")
    private val getAid = NamedCall("getAid")

    override fun install(scope: FeatureScope) {
        val bind = resolver.methods(hostClassLoader, ViewQueries.TIME_BIND).single().apply { isAccessible = true }
        val params = ITEM_PARAMS.toClass(hostClassLoader)
        val ui = labels()
        scope.own(AutoCloseable { ui.restoreAll() })
        scope.hooks.install(bind, HookSpec(
            id = "Gradient",
            // The old label can stay untouched when the model has no time, so the styling goes before the host decides.
            before = { call -> call.receiver?.callByName("getView")?.let(ui::clear) },
            after = { call ->
                if (call.outcome !is CallOutcome.Returned) return@HookSpec
                val presenter = call.receiver ?: return@HookSpec
                val model = call.argument(0)?.takeIf(params::isInstance) ?: return@HookSpec
                val aweme = getAweme.of(model)?.invoke(model) ?: return@HookSpec
                val created = (createTime.of(aweme)?.get(aweme) as? Number)?.toLong() ?: return@HookSpec
                if (created <= 0 || created > Long.MAX_VALUE / 1000) return@HookSpec
                val location = ipAttribution.of(aweme)?.get(aweme) as? String ?: return@HookSpec
                val root = presenter.callByName("getView") ?: return@HookSpec
                val (start, end) = LocationGradientText.colorsFor(getAid.of(aweme)?.invoke(aweme) as? String, location, seed)
                ui.decorate(root, location, start, end)
            },
        ))
    }

    companion object : TikTokFeature {
        private const val ITEM_PARAMS = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(RandomLocationGradient(resolver, hostClassLoader, locationLabels)) }
    }
}

// The avatar preview gestures. All of it runs on the main thread.
internal interface AvatarPreviews {
    // Follows the preview's image so a long press on it calls onLongPress; unbind forgets it again.
    fun bind(activity: Any, image: Any, onLongPress: () -> Unit)
    fun unbind(activity: Any)

    // Watches a touch the preview's own listener is about to handle, without changing it.
    fun touch(view: Any, event: Any)
    fun clear()
}

// Long-press the image in another user's avatar preview to save it to Pictures/douyin/avatar. The host's own gestures
// (pinch to zoom, drag to dismiss, tap) are only watched, never taken.
@HookEntry
internal class SaveOtherAvatar(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val previews: () -> AvatarPreviews,
    private val files: () -> MediaFiles,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ViewQueries.AVATAR_BIND, ViewQueries.AVATAR_TOUCH, ViewQueries.AVATAR_DESTROY)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val (bind, touch, destroy) = resolver.methods(hostClassLoader, ViewQueries.AVATAR_BIND, ViewQueries.AVATAR_TOUCH, ViewQueries.AVATAR_DESTROY)
        listOf(bind, touch, destroy).forEach { it.isAccessible = true }
        val ui = previews()
        val media = files()
        scope.own(AutoCloseable { ui.clear() })
        scope.hooks.install(bind, HookSpec(id = "Bind", after = { call ->
            if (call.outcome !is CallOutcome.Returned) return@HookSpec
            val activity = call.receiver ?: return@HookSpec
            val image = call.argument(0)
            val urls = mediaUrls((call.argument(3) as? Array<*>)?.filterIsInstance<String>().orEmpty())
            if (image != null && call.argument(2) != null && urls.isNotEmpty()) ui.bind(activity, image) { ask(media, activity, urls) } else ui.unbind(activity)
        }))
        scope.hooks.install(touch, HookSpec(id = "Touch", before = { call ->
            val view = call.argument(0)
            val event = call.argument(1)
            if (view != null && event != null) ui.touch(view, event)
        }))
        scope.hooks.install(destroy, HookSpec(id = "Destroy", before = { call -> call.receiver?.let(ui::unbind) }))
    }

    private fun ask(media: MediaFiles, activity: Any, urls: List<String>) {
        prompts.confirm(R.string.host_avatar_save_title, R.string.host_avatar_save_body, emptyArray(), R.string.host_avatar_save_confirm) {
            if (media.requestStoragePermission(activity)) return@confirm prompts.toast(R.string.host_notice_media_permission)
            val key = urls.first()
            if (!pending.add(key)) return@confirm
            prompts.toast(R.string.host_notice_media_saving)
            runner.run(0) {
                try {
                    media.save(activity, urls, false, ImageFolder.AVATAR)
                    logger.log("feature=$ID saved")
                    prompts.toast(R.string.host_notice_avatar_saved)
                } catch (failure: java.io.IOException) {
                    fail(failure)
                } catch (failure: IllegalStateException) {
                    fail(failure)
                } catch (failure: IllegalArgumentException) {
                    fail(failure)
                } finally {
                    pending.remove(key)
                }
            }
        }
    }

    private fun fail(failure: Exception) {
        logger.log("feature=$ID save_failed ${failure.javaClass.simpleName}")
        prompts.toast(R.string.host_notice_media_failed, (failure.message ?: failure.javaClass.simpleName).take(REASON_LENGTH))
    }

    companion object : TikTokFeature {
        const val ID = "SaveOtherAvatar"
        private const val REASON_LENGTH = 200

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(SaveOtherAvatar(resolver, hostClassLoader, avatarPreviews, mediaFiles, prompts(ID), logger))
        }
    }
}
