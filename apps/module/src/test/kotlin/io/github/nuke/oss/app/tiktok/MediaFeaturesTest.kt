package io.github.nuke.oss.app.tiktok

import com.ss.android.ugc.aweme.feed.model.VideoItemParams
import com.ss.android.ugc.aweme.feed.share.video.SharePrivacyVideoApi
import com.ss.android.ugc.awemepushapi.FailedType
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.feed.AllowFeedDownloads
import io.github.nuke.oss.app.tiktok.feature.feed.BypassListenFilter
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadOriginalImages
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadOriginalVideo
import io.github.nuke.oss.app.tiktok.feature.feed.LocationGradientText
import io.github.nuke.oss.app.tiktok.feature.feed.LongPressCopy
import io.github.nuke.oss.app.tiktok.feature.feed.SaveCommentMedia
import io.github.nuke.oss.app.tiktok.feature.feed.SaveCommentOriginalImage
import io.github.nuke.oss.app.tiktok.feature.feed.SaveOtherAvatar
import io.github.nuke.oss.app.tiktok.feature.feed.VideoBottomInfo
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.feature.general.BlockNotificationPrompts
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.query.PermissionQueries
import io.github.nuke.oss.app.tiktok.query.ViewQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import io.github.nuke.oss.app.tiktok.sdk.media.mediaPaths
import io.github.nuke.oss.app.tiktok.sdk.media.mediaUrls
import io.github.nuke.oss.core.Logger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the notification, download, copy, avatar and time-location features, with stand-in host classes.
class MediaFeaturesTest : HookHarness() {
    private val logged = ArrayList<String>()
    private val logger = Logger { logged += it }
    private val prompts = RecordingPrompts()
    private val scheduler = ManualScheduler()
    private val inline = BackgroundRunner { _, task -> task() }

    // ---- notification prompts
    class GuideListener { val failed = ArrayList<Any>(); fun onLoadFailed(type: Any) { failed += type } }
    class GuideLoader { fun load(a: Any?, b: Any?, listener: GuideListener?, c: Any?, d: Any?, e: String?): String = "ran" }
    class AlertCallback { val failures = ArrayList<Pair<String, Boolean>>(); fun fail(reason: String, flag: Boolean) { failures += reason to flag } }
    class AlertShower { fun show(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?, f: Any?, callback: AlertCallback?, g: Any?): String = "ran" }
    class Requester { var asked = 0; fun requestPermissions(permissions: Array<String>, code: Int) { asked++ } }

    private val permission = RecordingNotificationPermission()
    private val guide = method(GuideLoader::class.java, "load", Any::class.java, Any::class.java, GuideListener::class.java, Any::class.java, Any::class.java, String::class.java)
    private val alert = method(AlertShower::class.java, "show", Any::class.java, Any::class.java, Any::class.java, Any::class.java, Any::class.java, Any::class.java, AlertCallback::class.java, Any::class.java)
    private val ask = method(Requester::class.java, "requestPermissions", Array<String>::class.java, Int::class.javaPrimitiveType!!)

    private fun notifications() = BlockNotificationPrompts(
        resolver(PermissionQueries.LOAD_GUIDE to listOf(guide), PermissionQueries.GOOGLE_ALERT to listOf(alert)),
        loader, scheduler, { permission }, logger, Requester::class.java,
    )

    @Test fun `the guide and the alert are closed through the host's failure path on the main thread`() {
        enable(notifications())
        val listener = GuideListener()
        assertNull(backend.call(guide, GuideLoader(), null, null, listener, null, null, "x"))
        assertTrue(listener.failed.isEmpty())
        scheduler.advance(0)
        assertEquals(listOf<Any>(FailedType.REVERSE_EXPERIMENT), listener.failed)
        val callback = AlertCallback()
        assertNull(backend.call(alert, AlertShower(), null, null, null, null, null, null, callback, null))
        scheduler.advance(0)
        assertEquals(listOf("Nuke notification permission guide disabled" to false), callback.failures)
    }

    @Test fun `a lone notification permission request is answered as denied and others pass through`() {
        enable(notifications())
        val requester = Requester()
        backend.call(ask, requester, arrayOf("android.permission.POST_NOTIFICATIONS"), 5)
        assertEquals(0, requester.asked)
        scheduler.advance(0)
        assertEquals(listOf<Pair<Any, Int>>(requester to 5), permission.denied)
        backend.call(ask, requester, arrayOf("android.permission.CAMERA"), 6)
        assertEquals(1, requester.asked)
        permission.pending = false
        backend.call(ask, requester, arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        assertEquals(2, requester.asked)
    }

    @Test fun `a suppressed request is still finished after the feature is turned off`() {
        val feature = notifications()
        enable(feature)
        val listener = GuideListener()
        backend.call(guide, GuideLoader(), null, null, listener, null, null, "x")
        disable(feature)
        scheduler.advance(0)
        assertEquals(1, listener.failed.size)
    }

    // ---- downloads
    class Video(@JvmField var download: Any? = "download", @JvmField var watermark: Boolean = true, @JvmField var suffix: Boolean = true) {
        fun play(): Any = "play"
    }
    class Aweme(private val video: Video?, @JvmField var multi: Boolean = false, @JvmField var images: List<Any?>? = null) {
        @JvmField var status = AwemeStatus()
        fun getVideo(): Video? = video
        fun isMultiImage() = multi
    }
    class AwemeStatus { @JvmField var downloadStatus = 3 }
    class Chooser { fun choose(aweme: Aweme): String = "original" }

    @Test fun `the video address is the playable one when the aweme has a video`() {
        val choose = method(Chooser::class.java, "choose", Aweme::class.java)
        enable(DownloadOriginalVideo(resolver(DownloadQueries.VIDEO_ADDRESS to listOf(choose), DownloadQueries.VIDEO_PLAY to listOf(method(Video::class.java, "play"))), loader))
        assertEquals("play", backend.call(choose, Chooser(), Aweme(Video())))
        assertEquals("original", backend.call(choose, Chooser(), Aweme(null)))
    }

    class ImageStruct { @JvmField var origin: Any? = "origin"; fun download(): Any = "download" }

    @Test fun `a comment image is saved from its original address`() {
        val getter = method(ImageStruct::class.java, "download")
        enable(SaveCommentOriginalImage(resolverOf(DownloadQueries.COMMENT_IMAGE_DOWNLOAD to getter, DownloadQueries.COMMENT_IMAGE_ORIGIN to ImageStruct::class.java.getDeclaredField("origin")), loader))
        assertEquals("origin", backend.call(getter, ImageStruct()))
        assertEquals("download", backend.call(getter, ImageStruct().apply { origin = null }))
    }

    class ListenFilter { fun accept(aweme: Any?, scene: String?): Boolean = false }

    @Test fun `the listen filter always accepts`() {
        val accept = method(ListenFilter::class.java, "accept", Any::class.java, String::class.java)
        enable(BypassListenFilter(resolver(DownloadQueries.LISTEN_FILTER to listOf(accept)), loader))
        assertEquals(true, backend.call(accept, ListenFilter(), null, "x"))
    }

    enum class Kind { DOWNLOAD, DUET }
    enum class PermissionStatus { NORMAL, GRAYED }
    class PermissionResult(@JvmField var status: PermissionStatus = PermissionStatus.GRAYED)
    class Checker(private val kind: Kind) {
        fun check(subject: Any?): PermissionResult = PermissionResult()
        fun kind(): Kind = kind
    }
    class Gallery { var ran = 0; fun download(aweme: Aweme, name: String) { ran++ } }
    class Observable(val value: Any?) { companion object { @JvmStatic fun just(value: Any?) = Observable(value) } }
    class Privacy { fun check(url: String): Observable = Observable("server") }

    private fun downloads(): AllowFeedDownloads {
        val check = method(Checker::class.java, "check", Any::class.java)
        return AllowFeedDownloads(
            resolverOf(
                DownloadQueries.PERMISSION to check, DownloadQueries.CHECKER_KIND to method(Checker::class.java, "kind"),
                DownloadQueries.GALLERY_DOWNLOAD to method(Gallery::class.java, "download", Aweme::class.java, String::class.java),
                DownloadQueries.PRIVACY_DOWNLOAD to method(Privacy::class.java, "check", String::class.java),
                DownloadQueries.PERMISSION_STATUS to PermissionResult::class.java.getDeclaredField("status"),
                DownloadQueries.AWEME_DOWNLOAD_STATUS to AwemeStatus::class.java.getDeclaredField("downloadStatus"),
                DownloadQueries.PRIVACY_STATUS to SharePrivacyVideoApi.PrivacyVideoResponse::class.java.getDeclaredField("status"),
                DownloadQueries.PRIVACY_MESSAGE to SharePrivacyVideoApi.PrivacyVideoResponse::class.java.getDeclaredField("msg"),
            ),
            loader,
        )
    }

    @Test fun `only the download permission checker reports normal`() {
        enable(downloads())
        val check = method(Checker::class.java, "check", Any::class.java)
        assertEquals(PermissionStatus.NORMAL, (backend.call(check, Checker(Kind.DOWNLOAD), null) as PermissionResult).status)
        assertEquals(PermissionStatus.GRAYED, (backend.call(check, Checker(Kind.DUET), null) as PermissionResult).status)
    }

    @Test fun `a gallery download sees no banned status and the privacy check answers allowed`() {
        enable(downloads())
        val aweme = Aweme(null)
        backend.call(method(Gallery::class.java, "download", Aweme::class.java, String::class.java), Gallery(), aweme, "n")
        assertEquals(0, aweme.status.downloadStatus)
        val answer = backend.call(method(Privacy::class.java, "check", String::class.java), Privacy(), "u") as Observable
        val response = answer.value as SharePrivacyVideoApi.PrivacyVideoResponse
        assertEquals(0, response.status)
        assertEquals("", response.msg)
    }

    class Image(@JvmField var urlList: List<String>?, @JvmField var downloadUrlList: List<String>? = null, @JvmField var video: Video? = null)
    class ImageDownloader { @JvmField var aweme: Aweme? = null; fun download(pack: Any?) {} }
    class ExecutorA { fun run(task: Any?): Boolean = true }
    class ExecutorB { fun run(task: Any?): Boolean = true }
    class Task(private val paths: List<String>) { fun paths(): List<String> = paths }
    class Masks { fun local(): Boolean = true; fun live(): Boolean = true }
    class Story { fun single(a: Any?, b: Any?, paths: Any?) {}; fun multi(a: Any?, b: Any?, paths: Any?) {} }

    @Test fun `image sets use their plain addresses and the files the host writes are converted`() {
        val files = RecordingMediaFiles()
        val download = method(ImageDownloader::class.java, "download", Any::class.java)
        val execute = method(ExecutorA::class.java, "run", Any::class.java)
        val live = method(ExecutorB::class.java, "run", Any::class.java)
        val story = method(Story::class.java, "single", Any::class.java, Any::class.java, Any::class.java)
        val multi = method(Story::class.java, "multi", Any::class.java, Any::class.java, Any::class.java)
        val local = method(Masks::class.java, "local")
        val liveMask = method(Masks::class.java, "live")
        enable(DownloadOriginalImages(
            resolverOf(
                DownloadQueries.IMAGE_DOWNLOAD to download, DownloadQueries.VIDEO_PLAY to method(Video::class.java, "play"),
                DownloadQueries.IMAGE_EXECUTOR to execute, DownloadQueries.LIVE_EXECUTOR to live,
                DownloadQueries.TASK_PATHS to method(Task::class.java, "paths"), DownloadQueries.LOCAL_MASK to local,
                DownloadQueries.LIVE_MASK to liveMask, DownloadQueries.STORY_SINGLE to story, DownloadQueries.STORY_MULTI to multi,
                DownloadQueries.DOWNLOAD_AWEME to ImageDownloader::class.java.getDeclaredField("aweme"),
                DownloadQueries.VIDEO_DOWNLOAD to Video::class.java.getDeclaredField("download"),
                DownloadQueries.VIDEO_WATERMARK to Video::class.java.getDeclaredField("watermark"),
                DownloadQueries.VIDEO_SUFFIX_WATERMARK to Video::class.java.getDeclaredField("suffix"),
            ),
            loader, { files }, logger,
        ))
        val clip = Video()
        val image = Image(listOf("u1"), video = clip)
        val owner = ImageDownloader().apply { aweme = Aweme(null, multi = true, images = listOf(image)) }
        backend.call(download, owner, null)
        assertEquals(listOf("u1"), image.downloadUrlList)
        assertEquals("play", clip.download)
        assertFalse(clip.watermark)
        assertFalse(clip.suffix)
        assertEquals(false, backend.call(local, Masks()))
        assertEquals(false, backend.call(liveMask, Masks()))
        val file = File.createTempFile("nuke-test", ".png")
        try {
            backend.call(execute, ExecutorA(), Task(listOf(file.path, "/no/such/file")))
            backend.call(story, Story(), null, null, listOf(listOf(file.path)))
            assertEquals(listOf(file, file), files.converted)
        } finally {
            file.delete()
        }
    }

    // ---- comment media
    class Url(private val urls: List<String>) { fun getUrlList(): List<String> = urls }
    class AudioStruct(@JvmField var content: String?)
    class Emoji(@JvmField var animateUrl: Url?, @JvmField var staticUrl: Url?)
    class Comment(@JvmField var commentAudio: AudioStruct? = null, @JvmField var emoji: Emoji? = null)
    class Params(@JvmField var comment: Comment?)
    class SaveItem(@JvmField var params: Params?) {
        var clicked = 0
        fun visible(): Boolean = false
        fun click(index: Int) { clicked++ }
        fun getActivity(): Any = "activity"
    }
    class WhiteList { fun list(params: Params): Set<String> = setOf("copy") }

    private fun commentMedia(audio: Boolean, files: RecordingMediaFiles = RecordingMediaFiles()) = SaveCommentMedia(
        if (audio) SaveCommentMedia.AUDIO_ID else SaveCommentMedia.EMOJI_ID, audio,
        resolverOf(
            DownloadQueries.SAVE_VISIBLE to method(SaveItem::class.java, "visible"),
            DownloadQueries.SAVE_CLICK to method(SaveItem::class.java, "click", Int::class.javaPrimitiveType!!),
            DownloadQueries.COMMENT_WHITE_LIST to method(WhiteList::class.java, "list", Params::class.java),
            DownloadQueries.SAVE_PARAMS to SaveItem::class.java.getDeclaredField("params"),
            DownloadQueries.PARAMS_COMMENT to Params::class.java.getDeclaredField("comment"),
            DownloadQueries.COMMENT_AUDIO_CONTENT to AudioStruct::class.java.getDeclaredField("content"),
        ),
        loader, { files }, prompts, logger, inline,
    )

    private val voice = Comment(commentAudio = AudioStruct("""{"video_list":[{"main_url":"https://a/1.mp3","backup_url":"//b/2.mp3"}]}"""))
    private val sticker = Comment(emoji = Emoji(Url(listOf("https://a/s.mp4")), Url(listOf("https://a/s.png"))))

    @Test fun `a voice comment gets the save entry and a click saves its audio`() {
        val files = RecordingMediaFiles()
        enable(commentMedia(true, files))
        val item = SaveItem(Params(voice))
        assertEquals(true, backend.call(method(SaveItem::class.java, "visible"), item))
        assertEquals(setOf("copy", "save_image"), backend.call(method(WhiteList::class.java, "list", Params::class.java), WhiteList(), item.params))
        backend.call(method(SaveItem::class.java, "click", Int::class.javaPrimitiveType!!), item, 0)
        assertEquals(0, item.clicked)
        assertEquals(listOf(listOf("https://a/1.mp3", "https://b/2.mp3")), files.saved.map { it.urls })
        assertTrue(files.saved.single().audio)
        assertEquals(listOf(R.string.host_notice_media_saving, R.string.host_notice_media_saved), prompts.toasts.map { it.first })
    }

    @Test fun `a sticker comment saves the animated address first and is not claimed by the audio kind`() {
        val files = RecordingMediaFiles()
        enable(commentMedia(false, files))
        val item = SaveItem(Params(sticker))
        assertEquals(true, backend.call(method(SaveItem::class.java, "visible"), item))
        backend.call(method(SaveItem::class.java, "click", Int::class.javaPrimitiveType!!), item, 0)
        assertEquals(listOf("https://a/s.mp4"), files.saved.single().urls)
        assertFalse(files.saved.single().audio)
    }

    @Test fun `a comment with nothing to save, a missing permission and a failed save are reported honestly`() {
        val files = RecordingMediaFiles()
        enable(commentMedia(true, files))
        val click = method(SaveItem::class.java, "click", Int::class.javaPrimitiveType!!)
        val plain = SaveItem(Params(Comment()))
        assertEquals(false, backend.call(method(SaveItem::class.java, "visible"), plain))
        backend.call(click, plain, 0)
        assertEquals(1, plain.clicked)
        files.needsPermission = true
        backend.call(click, SaveItem(Params(voice)), 0)
        assertEquals(R.string.host_notice_media_permission, prompts.toasts.last().first)
        assertTrue(files.saved.isEmpty())
        files.needsPermission = false
        files.failure = IllegalStateException("No media URL succeeded")
        backend.call(click, SaveItem(Params(voice)), 0)
        assertEquals(R.string.host_notice_media_failed, prompts.toasts.last().first)
        assertEquals(listOf<Any>("No media URL succeeded"), prompts.toasts.last().second)
    }

    // ---- long-press copy
    class CopyItem(private val text: String, private val shown: Boolean) { fun text(): String = text; fun shown(): Boolean = shown }
    class CopyRegister { fun create(registry: Any?): Any = CopyItem("comment text", true) }
    class SignatureView(private val signature: String) {
        fun bind(text: String, list: List<*>) {}
        fun signature(): String = signature
        fun performLongClick(): Boolean = false
    }
    class Mensa(private val aweme: Any) { fun aweme(): Any = aweme }
    class CaptionListener(@JvmField var context: Mensa?)
    class CaptionVideo(@JvmField var desc: String)
    class DescFrame(var bound: String = "a", var current: String = "a") {
        @JvmField var tempIdentifier: String = bound
        @JvmField var uniqueIdentifier: String = current
        @JvmField var performedLongPress = false
        var ran = 0
        fun getLongPressListener(): Any = CaptionListener(Mensa(CaptionVideo("caption text")))
        fun trigger() { ran++ }
    }

    private fun copy(vararg targets: String): Pair<LongPressCopy, RecordingTextCopier> {
        val copier = RecordingTextCopier()
        val options = values(LongPressCopy.ID, LongPressCopy.OPTIONS, LongPressCopy.TARGETS to targets.toSet())
        val feature = LongPressCopy(
            resolverOf(
                ViewQueries.COMMENT_COPY_ITEM to method(CopyRegister::class.java, "create", Any::class.java),
                ViewQueries.COMMENT_COPY_TEXT to method(CopyItem::class.java, "text"),
                ViewQueries.COMMENT_COPY_VISIBLE to method(CopyItem::class.java, "shown"),
                ViewQueries.SIGNATURE_BIND to method(SignatureView::class.java, "bind", String::class.java, List::class.java),
                ViewQueries.SIGNATURE_TEXT to method(SignatureView::class.java, "signature"),
                ViewQueries.CAPTION_LONG_PRESS to method(DescFrame::class.java, "trigger"),
                ViewQueries.CAPTION_AWEME to method(Mensa::class.java, "aweme"),
                ViewQueries.CAPTION_CONTEXT to CaptionListener::class.java.getDeclaredField("context"),
            ),
            loader, options, { copier }, SignatureView::class.java,
        )
        return feature to copier
    }

    @Test fun `opening a comment menu copies its text`() {
        val (feature, copier) = copy(LongPressCopy.COMMENT)
        enable(feature)
        backend.call(method(CopyRegister::class.java, "create", Any::class.java), CopyRegister(), null)
        assertEquals(listOf("comment text"), copier.copied)
    }

    @Test fun `a long press on the signature copies it and the host's press is not run`() {
        val (feature, copier) = copy(LongPressCopy.SIGNATURE)
        enable(feature)
        val view = SignatureView("hello")
        backend.call(method(SignatureView::class.java, "bind", String::class.java, List::class.java), view, "x", emptyList<Any>())
        assertEquals(listOf<Any>(view), copier.longClickable)
        assertEquals(true, backend.call(method(SignatureView::class.java, "performLongClick"), view))
        assertEquals(listOf("hello"), copier.copied)
    }

    @Test fun `a caption press copies the description and a stale press is left alone`() {
        val (feature, copier) = copy(LongPressCopy.CAPTION)
        enable(feature)
        val trigger = method(DescFrame::class.java, "trigger")
        val frame = DescFrame()
        backend.call(trigger, frame)
        assertEquals(listOf("caption text"), copier.copied)
        assertEquals(0, frame.ran)
        assertTrue(frame.performedLongPress)
        assertEquals(listOf<Any>(frame), copier.confirmed)
        val stale = DescFrame().apply { uniqueIdentifier = "other" }
        backend.call(trigger, stale)
        assertEquals(1, stale.ran)
        assertEquals(1, copier.copied.size)
    }

    @Test fun `with nothing chosen nothing is hooked`() {
        val (feature, _) = copy()
        enable(feature)
        assertEquals(0, backend.attachments.get())
    }

    // ---- time and location
    class Post(@JvmField var createTime: Long, private val live: Boolean = false, private val ad: Boolean = false) {
        fun isLive() = live
        fun getAd() = ad
    }
    class Label { fun visibility(model: Any?): Int = 8; fun mode(): Int = 0 }

    @Test fun `the time and location label shows for posts with a usable time`() {
        val visibility = method(Label::class.java, "visibility", Any::class.java)
        val mode = method(Label::class.java, "mode")
        enable(VideoBottomInfo(
            resolver(ViewQueries.TIME_VISIBILITY to listOf(visibility), ViewQueries.TIME_IP_MODE to listOf(mode)), loader,
            FeedEvents(resolver(), loader), values(VideoBottomInfo.ID, VideoBottomInfo.OPTIONS, "line" to false, "host-label" to true), { error("no line") },
        ))
        assertEquals(1, backend.call(mode, Label()))
        assertEquals(0, backend.call(visibility, Label(), VideoItemParams(Post(1_700_000_000L))))
        assertEquals(8, backend.call(visibility, Label(), VideoItemParams(Post(0L))))
        assertEquals(8, backend.call(visibility, Label(), VideoItemParams(Post(5L, live = true))))
        assertEquals(8, backend.call(visibility, Label(), VideoItemParams(Post(5L, ad = true))))
        assertEquals(8, backend.call(visibility, Label(), "not a model"))
    }

    @Test fun `the location is found at the end of the label after the time and two spaces`() {
        assertEquals(12..13, LocationGradientText.regionRange("2026-10-28  广东", "广东"))
        assertNull(LocationGradientText.regionRange("2026-10-28 广东", "广东"))
        assertNull(LocationGradientText.regionRange("  广东", "广东"))
        assertNull(LocationGradientText.regionRange("2026-10-28  广东", ""))
        assertNull(LocationGradientText.regionRange("2026-10-28  广东", "北京"))
    }

    @Test fun `a post keeps its colours for a run`() {
        assertEquals(LocationGradientText.colorsFor("p1", "x", 7), LocationGradientText.colorsFor("p1", "y", 7))
        assertEquals(LocationGradientText.colorsFor(null, "x", 7), LocationGradientText.colorsFor("", "x", 7))
    }

    // ---- avatar
    class AvatarScreen {
        fun bind(image: Any?, flag: Boolean, user: Any?, urls: Array<String>?) {}
        fun onDestroy() {}
    }
    class AvatarTouch { fun onTouch(view: Any?, event: Any?): Boolean = false }

    @Test fun `a long press on the avatar asks, then saves to the avatar folder once`() {
        val previews = RecordingAvatarPreviews()
        val files = RecordingMediaFiles()
        val bind = method(AvatarScreen::class.java, "bind", Any::class.java, Boolean::class.javaPrimitiveType!!, Any::class.java, Array<String>::class.java)
        val destroy = method(AvatarScreen::class.java, "onDestroy")
        val touch = method(AvatarTouch::class.java, "onTouch", Any::class.java, Any::class.java)
        enable(SaveOtherAvatar(
            resolver(ViewQueries.AVATAR_BIND to listOf(bind), ViewQueries.AVATAR_TOUCH to listOf(touch), ViewQueries.AVATAR_DESTROY to listOf(destroy)),
            loader, { previews }, { files }, prompts, logger, inline,
        ))
        val screen = AvatarScreen()
        backend.call(bind, screen, "image", true, "user", arrayOf("https://a/avatar.jpg"))
        val (image, onLongPress) = previews.bound.getValue(screen)
        assertEquals("image", image)
        onLongPress()
        assertEquals(1, prompts.confirms.size)
        prompts.confirms.single().third()
        assertEquals(listOf(listOf("https://a/avatar.jpg")), files.saved.map { it.urls })
        assertEquals(ImageFolder.AVATAR, files.saved.single().folder)
        assertEquals(R.string.host_notice_avatar_saved, prompts.toasts.last().first)
        backend.call(touch, AvatarTouch(), "view", "event")
        assertEquals(listOf<Pair<Any, Any>>("view" to "event"), previews.touches)
        backend.call(destroy, screen)
        assertTrue(previews.bound.isEmpty())
        backend.call(bind, screen, "image", true, null, arrayOf("https://a/avatar.jpg"))
        assertTrue(previews.bound.isEmpty())
    }

    // ---- plain helpers
    @Test fun `media addresses are normalized, decoded and filtered`() {
        assertEquals(listOf("https://a/1", "http://b/2", "https://c/3"), mediaUrls(listOf(" https://a/1 ", "http://b/2", "//c/3", "ftp://x", "not base64!", "https://a/1")))
        val encoded = java.util.Base64.getEncoder().encodeToString("https://cdn/x.mp3".toByteArray())
        assertEquals(listOf("https://cdn/x.mp3"), mediaUrls(listOf(encoded)))
        assertEquals(listOf("a", "b"), mediaPaths(listOf("a", listOf("b", listOf("a", ""))), 0))
        assertTrue(mediaPaths(null).isEmpty())
    }

    @Test fun `file types come from the first bytes and an unknown format is an error`() {
        fun detect(vararg bytes: Int, audio: Boolean = false) = MediaFileType.detect(ByteArray(bytes.size) { bytes[it].toByte() }, audio)
        assertEquals("png", detect(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).extension)
        assertEquals("jpg", detect(0xff, 0xd8, 0xff, 0xe0).extension)
        val ftyp = { brand: String -> byteArrayOf(0, 0, 0, 0x18) + "ftyp$brand".toByteArray() + ByteArray(8) }
        assertEquals("image/vvic", MediaFileType.detect(ftyp("vvic")).mime)
        assertEquals("video/mp4", MediaFileType.detect(ftyp("isom")).mime)
        assertEquals("audio/mp4", MediaFileType.detect(ftyp("isom"), audio = true).mime)
        assertTrue(runCatching { detect(1, 2, 3) }.isFailure)
        assertEquals("mp3", detect(0x49, 0x44, 0x33, 0x04).extension)
    }
}
