package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.FilePicker
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.PickResult
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.MessageSendTime
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.io.IOException

// Restyles a comment list page's View tree: transparent clears the backgrounds so the video shows through, textColor
// recolors its text and input fields, and background, when given, becomes the page's own background image. The page
// keeps growing as comments load, so the style is applied again as it lays out, for as long as the page View is attached.
// Main thread only.
internal interface CommentPanelStyle {
    fun apply(page: Any, transparent: Boolean, textColor: Int?, background: File?)
}

// Comment panel customisation, for the View-based comment list: the page View gets transparent backgrounds,
// a chosen text color and, with background on, an image of your choice once its onCreateView returns. The image is
// chosen with "Comment background" in the share panel (the system picker), copied into the host's private storage and
// read again whenever a comment page is built, so a new image shows from the next comment panel on. The Compose input bar is not
// customised: its class is obfuscated in the current host.
@HookEntry
internal class CommentPanelLook(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val style: () -> CommentPanelStyle,
    options: OptionValues,
    private val backgroundFile: File,
    private val entries: SharePanelEntries,
    private val picker: () -> FilePicker,
    private val prompts: HostPrompts,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.LIST_PAGE) + SharePanelEntries.QUERIES
    private val transparent = options.toggle(TRANSPARENT)
    private val colorText = options.text(TEXT_COLOR)
    private val background = options.toggle(BACKGROUND)

    override fun install(scope: FeatureScope) {
        val color = colorText.takeIf { it.isNotBlank() }?.let { MessageSendTime.parseColor(it) ?: error("$it is not a #RRGGBB color") }
        check(transparent || color != null || background) { "Nothing to change: choose transparent, a text color or a background" }
        val page = resolver.methods(hostClassLoader, CommentQueries.LIST_PAGE).single().declaringClass
        val created = page.resolve().optional(silent = true).method {
            name = "onCreateView"
            parameters { it.size == 3 && it[0].name == "android.view.LayoutInflater" && it[1].name == "android.view.ViewGroup" && it[2].name == "android.os.Bundle" }
            isSynthetic = false
        }.map { it.self }
        check(created.size == 1) { "Expected one onCreateView in ${page.name}, found ${created.size}" }
        val onCreateView = created.single().apply { isAccessible = true }
        val ui = style()
        if (background) chooseBackground(scope)
        scope.hooks.install(onCreateView, HookSpec(id = "PageView", after = { call ->
            val view = (call.outcome as? CallOutcome.Returned)?.value
            if (view != null) ui.apply(view, transparent, color, backgroundFile.takeIf { background && it.isFile })
        }))
    }

    // "Comment background" in the share panel: choose an image, or remove the one in use.
    private fun chooseBackground(scope: FeatureScope) {
        prompts.install(scope)
        val files = picker()
        files.install(scope, ID)
        entries.add(scope, ID, SharePanelEntry(ID, R.string.host_comment_background_entry, android.R.drawable.ic_menu_gallery) { _, _, dismiss ->
            dismiss()
            val items = listOf<Any>(R.string.host_comment_background_choose, R.string.host_comment_background_remove)
            prompts.choose(R.string.host_comment_background_entry, items, android.R.string.cancel, onUnavailable = {}, onCancel = {}) { index ->
                if (index == 1) {
                    backgroundFile.delete()
                    return@choose prompts.toast(R.string.host_comment_background_removed)
                }
                files.pick(listOf("image/*"), MAX_IMAGE_BYTES) { picked ->
                    when (picked) {
                        is PickResult.Cancelled -> Unit
                        is PickResult.Failed -> prompts.toast(R.string.host_comment_background_failed, picked.reason)
                        is PickResult.Picked -> keep(picked.file)
                    }
                }
            }
        })
    }

    // Runs on the picker's background thread; the picker's copy is moved into place in one step.
    private fun keep(picked: File) {
        val parent = backgroundFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return prompts.toast(R.string.host_comment_background_failed, "no directory")
        val staged = File(backgroundFile.path + ".tmp")
        try {
            picked.copyTo(staged, overwrite = true)
            if (!staged.renameTo(backgroundFile)) throw IOException("Cannot replace the background")
            prompts.toast(R.string.host_comment_background_set)
        } catch (failure: IOException) {
            logger.log("feature=$ID background_failed ${failure.javaClass.simpleName}")
            prompts.toast(R.string.host_comment_background_failed, failure.javaClass.simpleName)
        } finally {
            staged.delete()
            picked.delete()
        }
    }

    companion object : TikTokFeature {
        const val ID = "CommentPanelLook"
        const val TRANSPARENT = "transparent"
        const val TEXT_COLOR = "text-color"
        const val BACKGROUND = "background"
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        val OPTIONS = listOf(ToggleOption(TRANSPARENT, default = true), TextOption(TEXT_COLOR, default = "", maxLength = 9), ToggleOption(BACKGROUND))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CommentPanelLook(
                resolver, hostClassLoader, commentPanelStyle, options(ID, OPTIONS), File(streakDirectory.resolveSibling("comments"), "background"),
                sharePanelEntries(), { filePicker("comment-background") }, prompts(ID), logger,
            ))
        }
    }
}
