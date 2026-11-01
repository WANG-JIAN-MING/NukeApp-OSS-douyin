package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
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
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// A comment kept by the module: one this account published, or one bookmarked from a long-press menu.
internal data class CommentRecord(val awemeId: String, val cid: String, val text: String, val createdAt: Long)

// A list of comments, newest last, kept in a JSON file in the host's private storage. At most capacity entries are kept
// and the oldest go first. Callers keep file work off the main thread.
internal class CommentLog(private val file: File, private val capacity: Int = 200) {
    @Synchronized fun add(comment: CommentRecord) {
        val kept = read().filterNot { it.cid == comment.cid } + comment
        write(kept.takeLast(capacity))
    }

    @Synchronized fun remove(cid: String) = write(read().filterNot { it.cid == cid })

    @Synchronized fun clear() = write(emptyList())

    @Synchronized fun read(): List<CommentRecord> {
        if (!file.isFile) return emptyList()
        val entries = MiniJson.array(MiniJson.parse(file.readText())) ?: return emptyList()
        return entries.mapNotNull { entry ->
            val values = MiniJson.obj(entry) ?: return@mapNotNull null
            val awemeId = values["aweme"] as? String ?: return@mapNotNull null
            val cid = values["cid"] as? String ?: return@mapNotNull null
            CommentRecord(awemeId, cid, values["text"] as? String ?: "", (values["at"] as? Number)?.toLong() ?: 0)
        }
    }

    private fun write(comments: List<CommentRecord>) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create ${parent.name}")
        val text = MiniJson.write(comments.map { mapOf("aweme" to it.awemeId, "cid" to it.cid, "text" to it.text, "at" to it.createdAt) })
        val temporary = File(file.path + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) throw IOException("Cannot replace ${file.name}")
    }
}

// What the public web comment list says about one comment. The status values are the server's: 1 is shown to everyone,
// 7 is waiting for review and 0 is shown to mutual followers only; anything else probably hides it.
internal sealed interface CommentVisibility {
    data class Listed(val status: Int) : CommentVisibility
    data object NotListed : CommentVisibility
    data class Failed(val reason: String) : CommentVisibility
}

// Finds the comment in a web comment list response, among the top-level comments and their replies.
internal fun commentVisibility(response: String, cid: String): CommentVisibility {
    val root = MiniJson.obj(MiniJson.parse(response)) ?: return CommentVisibility.Failed("not JSON")
    val code = (root["status_code"] as? Number)?.toInt()
    if (code != null && code != 0) return CommentVisibility.Failed("status_code $code")
    for (comment in MiniJson.array(root["comments"]).orEmpty()) {
        val values = MiniJson.obj(comment) ?: continue
        val found = if (values["cid"] == cid) values
            else MiniJson.array(values["reply_comment"]).orEmpty().mapNotNull { MiniJson.obj(it) }.firstOrNull { it["cid"] == cid }
        if (found != null) return CommentVisibility.Listed((found["status"] as? Number)?.toInt() ?: return CommentVisibility.Failed("no status"))
    }
    return CommentVisibility.NotListed
}

// Asks the public web comment list for one comment. The insert_ids parameter makes the server put that comment first
// when it is listed at all.
internal fun interface CommentLookup {
    fun fetch(awemeId: String, cid: String, cookie: String): String
}

// The request: the web comment list, signed in with the host's own web session cookie and the desktop client's
// headers. The cookie only ever goes to www.douyin.com and is never logged.
internal object WebCommentLookup : CommentLookup {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) douyin/7.8.201 " +
        "Chrome/136.0.7103.59 Electron/36.4.0-rs.29.release.pgo.0 TTElectron/36.4.0-rs.29.release.pgo.0 Safari/537.36 " +
        "awemePcClient/7.8.201 buildId/364345186 osName/Windows"

    override fun fetch(awemeId: String, cid: String, cookie: String): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val url = URI("https://www.douyin.com/aweme/v1/web/comment/list/?aid=6383&aweme_id=${encode(awemeId)}&cursor=0&count=1" +
            "&insert_ids=${encode(cid)}&cookie_enabled=true").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Language", "zh-CN")
            connection.setRequestProperty("Referer", "https://www.douyin.com/jingxuan")
            connection.setRequestProperty("Cookie", cookie)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_BYTES) throw IOException("Response too large")
                }
                buffer.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}

// Opens a post's comment panel at one comment. Main thread only.
internal interface CommentPages {
    fun open(activity: Any, awemeId: String, cid: String)
}

// Checks whether the comments you publish can be seen by others ("did my comment go through"). Each
// comment the host reports published is kept (post ID, comment ID, text and time) in the host's private storage. With
// auto-check on, the comment is looked up once after a delay and a toast says what the public web comment list shows.
// "My comments" in the share panel lists the kept comments; picking one looks it up again, and the result dialog can
// open the comment in its post. A lookup uses this account's web session cookie, which the host keeps for its own web
// requests, so it only works while that cookie is there. Comments in places without a public comment list (friends
// only, private posts) cannot be checked.
@HookEntry
internal class CheckSentComments(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val log: CommentLog,
    private val entries: SharePanelEntries,
    private val pages: () -> CommentPages,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val lookup: CommentLookup = WebCommentLookup,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.PUBLISH_SUCCESS, CommentQueries.WEB_COOKIE) + SharePanelEntries.QUERIES
    private val autoCheck = options.toggle(AUTO_CHECK)
    private val delaySeconds = options.number(DELAY_SECONDS)
    private val responseComment = NamedField("comment")
    private val cid = NamedField("cid")
    private val awemeId = NamedField("awemeId")
    private val text = NamedField("text")
    private val createTime = NamedField("createTime")

    override fun install(scope: FeatureScope) {
        val (published, cookieReader) = resolver.methods(hostClassLoader, CommentQueries.PUBLISH_SUCCESS, CommentQueries.WEB_COOKIE)
        check(java.lang.reflect.Modifier.isStatic(cookieReader.modifiers)) { "${cookieReader.declaringClass.name}.${cookieReader.name} is not static" }
        published.isAccessible = true
        cookieReader.isAccessible = true
        prompts.install(scope)
        val ui = pages()
        val active = AtomicBoolean(true)
        scope.own(AutoCloseable { active.set(false) })
        val cookie = { (cookieReader.invoke(null) as? String)?.takeIf { it.isNotBlank() } }
        scope.hooks.install(published, HookSpec(id = "Published", after = { call ->
            if (call.outcome !is CallOutcome.Returned) return@HookSpec
            val comment = call.argument(0)?.let { response -> responseComment.of(response)?.get(response) } ?: return@HookSpec
            val sent = read(comment) ?: return@HookSpec
            runner.run(0) {
                try {
                    log.add(sent)
                } catch (failure: IOException) {
                    logger.log("feature=$ID record_failed ${failure.javaClass.simpleName}")
                }
            }
            if (autoCheck) runner.run(delaySeconds * 1000) {
                if (active.get()) report(lookUp(sent, cookie), open = null)
            }
        }))
        entries.add(scope, ID, SharePanelEntry(ID, R.string.host_sent_comments_entry, android.R.drawable.ic_menu_agenda) { activity, _, dismiss ->
            dismiss()
            val held = WeakReference(activity)
            runner.run(0) {
                val kept = try { log.read().asReversed() } catch (failure: IOException) {
                    logger.log("feature=$ID read_failed ${failure.javaClass.simpleName}")
                    return@run prompts.toast(R.string.host_sent_comments_unreadable)
                }
                if (kept.isEmpty()) return@run prompts.toast(R.string.host_sent_comments_empty)
                val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val items = kept.map { "${format.format(Date(it.createdAt * 1000))}  ${it.text.take(PREVIEW)}" }
                prompts.choose(R.string.host_sent_comments_title, items, android.R.string.cancel, onUnavailable = {}, onCancel = {}) { index ->
                    val chosen = kept[index]
                    runner.run(0) {
                        if (active.get()) report(lookUp(chosen, cookie)) { held.get()?.let { ui.open(it, chosen.awemeId, chosen.cid) } }
                    }
                }
            }
        })
    }

    private fun read(comment: Any): CommentRecord? {
        fun string(field: NamedField) = field.of(comment)?.get(comment) as? String
        val commentId = string(cid)?.takeIf { it.isNotBlank() } ?: return null
        val postId = string(awemeId)?.takeIf { it.isNotBlank() } ?: return null
        val at = (createTime.of(comment)?.get(comment) as? Number)?.toLong() ?: System.currentTimeMillis() / 1000
        return CommentRecord(postId, commentId, string(text).orEmpty(), at)
    }

    // Runs off the main thread: the cookie read and the request both block.
    private fun lookUp(comment: CommentRecord, cookie: () -> String?): CommentVisibility {
        val session = try {
            cookie()
        } catch (failure: ReflectiveOperationException) {
            return CommentVisibility.Failed("cookie ${(failure.cause ?: failure).javaClass.simpleName}")
        } ?: return CommentVisibility.Failed("no cookie")
        return try {
            commentVisibility(lookup.fetch(comment.awemeId, comment.cid, session), comment.cid)
        } catch (failure: IOException) {
            CommentVisibility.Failed(failure.javaClass.simpleName)
        } catch (failure: IllegalArgumentException) {
            CommentVisibility.Failed(failure.javaClass.simpleName)
        }
    }

    // A toast without open (the automatic check), a dialog offering to open the comment with it. The log keeps the outcome
    // kind only, never the comment, its IDs or the cookie.
    private fun report(result: CommentVisibility, open: (() -> Unit)?) {
        logger.log("feature=$ID checked ${when (result) {
            is CommentVisibility.Listed -> "status=${result.status}"
            CommentVisibility.NotListed -> "not_listed"
            is CommentVisibility.Failed -> "failed ${result.reason}"
        }}")
        val (message, args) = when (result) {
            is CommentVisibility.Listed -> when (result.status) {
                VISIBLE -> R.string.host_sent_comment_visible to emptyArray<Any>()
                REVIEWING -> R.string.host_sent_comment_reviewing to emptyArray()
                MUTUAL_ONLY -> R.string.host_sent_comment_mutual to emptyArray()
                else -> R.string.host_sent_comment_hidden to arrayOf<Any>(result.status)
            }
            CommentVisibility.NotListed -> R.string.host_sent_comment_not_listed to emptyArray()
            is CommentVisibility.Failed -> R.string.host_sent_comment_failed to emptyArray()
        }
        if (open == null) return prompts.toast(message, *args)
        prompts.confirm(R.string.host_sent_comments_title, message, args, R.string.host_sent_comment_open, onConfirm = open)
    }

    companion object : TikTokFeature {
        const val ID = "CheckSentComments"
        const val AUTO_CHECK = "auto-check"
        const val DELAY_SECONDS = "delay-seconds"
        private const val VISIBLE = 1
        private const val REVIEWING = 7
        private const val MUTUAL_ONLY = 0
        private const val PREVIEW = 30
        val OPTIONS = listOf(ToggleOption(AUTO_CHECK, default = true), NumberOption(DELAY_SECONDS, default = 20, min = 5, max = 600))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CheckSentComments(
                resolver, hostClassLoader, options(ID, OPTIONS), CommentLog(File(streakDirectory.resolveSibling("comments"), "sent.json")),
                sharePanelEntries(), commentPages, prompts(ID), logger,
            ))
        }
    }
}
