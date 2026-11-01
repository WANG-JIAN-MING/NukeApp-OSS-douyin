package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.feature.feed.TextCopier
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import io.github.nuke.oss.app.shared.dex.UsesDexQueries

// What the details dialog shows about a comment, read from the host's Comment when its long-press menu opens.
internal data class CommentInfo(
    val author: String, val authorId: String, val time: String, val likes: Int, val replies: Long, val region: String,
    val status: Int, val commentId: String, val postId: String, val text: String, val createdAt: Long,
)

// One button of the row: a module string for its label and what a tap does.
internal class RowButton(val label: Int, val onClick: () -> Unit)

// Adds a row of buttons to a long-press menu. attach is called with the menu's scroll view right after it is built; the
// row goes in once the menu has its content. Main thread only.
internal interface CommentDetailsRow {
    fun attach(panel: Any, buttons: List<RowButton>)
}

// "长按评论后添加更多功能": the long-press menu of a comment gets a
// row with "Comment details", which shows the author, time, likes, replies, region, status and IDs and copies the text,
// and with bookmarks on, "Bookmark", which keeps the comment in a list. "Bookmarked comments" in the share panel lists
// them, newest first, and opens the chosen one in its post; the list keeps at most 200 and can be cleared there. The
// menu view is shared with the video long-press menu, so the row is only added when a comment's menu was asked for at
// about the same time.
@HookEntry
internal class CommentDetails(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val row: () -> CommentDetailsRow,
    private val prompts: HostPrompts,
    private val copier: () -> TextCopier,
    options: OptionValues,
    private val marks: CommentLog,
    private val entries: SharePanelEntries,
    private val pages: () -> CommentPages,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
    private val commentName: String = CleanCommentCopy.COMMENT,
    private val panelName: String = PANEL,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.LONG_PRESS_PANEL) + SharePanelEntries.QUERIES
    private val bookmarks = options.toggle(BOOKMARKS)
    private val fields = listOf("cid", "awemeId", "text", "createTime", "diggCount", "replyCommentTotal", "ipLabel", "status", "user")
        .associateWith(::NamedField)
    private val nickname = NamedField("nickname")
    private val uid = NamedField("uid")

    // A comment's menu being asked for and a menu view being built, each with when it happened. Which comes first is not
    // known, so the later one pairs them; both are dropped once paired or when they are too far apart.
    private class Seen<T>(val value: T, val at: Long)

    override fun install(scope: FeatureScope) {
        val provider = resolver.methods(hostClassLoader, CommentQueries.LONG_PRESS_PANEL).single().apply { isAccessible = true }
        val finder = ChatMessageFinder(commentName.toClass(hostClassLoader))
        val panel = panelName.toClass(hostClassLoader)
        val built = panel.declaredConstructors.filter { it.parameterCount == 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
        check(built.size == 1) { "Expected one (Context, AttributeSet, int) constructor in ${panel.name}, found ${built.size}" }
        prompts.install(scope)
        val clipboard = copier()
        clipboard.install(scope, ID)
        val ui = row()
        val comment = AtomicReference<Seen<CommentInfo>?>()
        val view = AtomicReference<Seen<WeakReference<Any>>?>()
        fun pair() {
            val asked = comment.get() ?: return
            val shown = view.get() ?: return
            if (abs(asked.at - shown.at) > FRESH_MS) return
            if (!comment.compareAndSet(asked, null) || !view.compareAndSet(shown, null)) return
            val target = shown.value.get() ?: return
            val info = asked.value
            val details = RowButton(R.string.host_comment_details_entry) {
                prompts.confirm(
                    R.string.host_comment_details_title, R.string.host_comment_details_message,
                    arrayOf<Any>(info.author, info.authorId, info.time, info.likes, info.replies, info.region, info.status, info.commentId, info.postId, info.text),
                    R.string.host_comment_details_copy,
                ) { clipboard.copy(info.text) }
            }
            val bookmark = RowButton(R.string.host_comment_bookmark) {
                runner.run(0) {
                    try {
                        marks.add(CommentRecord(info.postId, info.commentId, info.text, info.createdAt))
                        prompts.toast(R.string.host_comment_bookmarked)
                    } catch (failure: IOException) {
                        logger.log("feature=$ID bookmark_failed ${failure.javaClass.simpleName}")
                        prompts.toast(R.string.host_comment_bookmark_failed)
                    }
                }
            }
            ui.attach(target, if (bookmarks && info.postId.isNotBlank()) listOf(details, bookmark) else listOf(details))
        }
        if (bookmarks) listBookmarks(scope)
        scope.hooks.install(provider, HookSpec(id = "CommentMenu", after = { call ->
            if (call.outcome !is CallOutcome.Returned) return@HookSpec
            val found = (0 until call.target.parameterCount).firstNotNullOfOrNull { finder.find(call.argument(it)) } ?: return@HookSpec
            read(found)?.let { comment.set(Seen(it, now())); pair() }
        }))
        scope.hooks.install(built.single().apply { isAccessible = true }, HookSpec(
            id = "MenuView",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call ->
                val menu = call.receiver
                if (call.outcome is CallOutcome.Returned && menu != null) {
                    view.set(Seen(WeakReference(menu), now()))
                    pair()
                }
            },
        ))
    }

    // "Bookmarked comments": a list whose first entry clears it; any other entry opens that comment.
    private fun listBookmarks(scope: FeatureScope) {
        val ui = pages()
        entries.add(scope, ID, SharePanelEntry(ID, R.string.host_comment_bookmarks_entry, android.R.drawable.btn_star) { activity, _, dismiss ->
            dismiss()
            val held = WeakReference(activity)
            runner.run(0) {
                val kept = try { marks.read().asReversed() } catch (failure: IOException) {
                    logger.log("feature=$ID read_failed ${failure.javaClass.simpleName}")
                    return@run prompts.toast(R.string.host_comment_bookmark_failed)
                }
                if (kept.isEmpty()) return@run prompts.toast(R.string.host_comment_bookmarks_empty)
                val items = listOf<Any>(R.string.host_comment_bookmarks_clear) + kept.map { it.text.take(PREVIEW).ifBlank { it.cid } }
                prompts.choose(R.string.host_comment_bookmarks_entry, items, android.R.string.cancel, onUnavailable = {}, onCancel = {}) { index ->
                    if (index == 0) {
                        prompts.confirm(R.string.host_comment_bookmarks_entry, R.string.host_comment_bookmarks_clear_confirm, emptyArray(), R.string.host_comment_bookmarks_clear) {
                            runner.run(0) {
                                try { marks.clear() } catch (failure: IOException) { logger.log("feature=$ID clear_failed ${failure.javaClass.simpleName}") }
                            }
                        }
                    } else {
                        val chosen = kept[index - 1]
                        held.get()?.let { ui.open(it, chosen.awemeId, chosen.cid) }
                    }
                }
            }
        })
    }

    private fun read(comment: Any): CommentInfo? {
        fun value(name: String) = fields.getValue(name).of(comment)?.get(comment)
        val commentId = (value("cid") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val user = value("user")
        val seconds = (value("createTime") as? Number)?.toLong() ?: 0
        return CommentInfo(
            author = user?.let { nickname.of(it)?.get(it) as? String }.orEmpty(),
            authorId = user?.let { uid.of(it)?.get(it) as? String }.orEmpty(),
            time = if (seconds > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000)) else "",
            likes = (value("diggCount") as? Number)?.toInt() ?: 0,
            replies = (value("replyCommentTotal") as? Number)?.toLong() ?: 0,
            region = value("ipLabel") as? String ?: "",
            status = (value("status") as? Number)?.toInt() ?: 0,
            commentId = commentId,
            postId = value("awemeId") as? String ?: "",
            text = value("text") as? String ?: "",
            createdAt = seconds,
        )
    }

    companion object : TikTokFeature {
        const val ID = "CommentDetails"
        const val BOOKMARKS = "bookmarks"
        val OPTIONS = listOf(ToggleOption(BOOKMARKS, default = true))
        private const val PREVIEW = 40
        const val PANEL = "com.ss.android.ugc.aweme.feed.ui.masklayer2.layout.FixGentleFlingNestedScrollView"
        // How long after a comment's menu was asked for its view may still be built.
        private const val FRESH_MS = 3_000L

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CommentDetails(
                resolver, hostClassLoader, commentDetailsRow, prompts(ID), textCopier, options(ID, OPTIONS),
                CommentLog(File(streakDirectory.resolveSibling("comments"), "marked.json")), sharePanelEntries(), commentPages, logger,
            ))
        }
    }
}
