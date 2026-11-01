package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.activity.Foreground
import io.github.nuke.oss.app.shared.activity.HostExit
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.SystemBars
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.settings.MemoryFeatureMemory
import io.github.nuke.oss.app.shared.settings.MemoryOptionStore
import io.github.nuke.oss.app.shared.settings.OptionRegistry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.feed.BottomInfoLabels
import io.github.nuke.oss.app.tiktok.feature.playback.ProgressLabel
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope

// Host UI stand-ins for feature tests: tasks run only when the test advances time, and prompts are recorded.
class ManualScheduler : TaskScheduler {
    private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()
    var now = 0L
        private set

    override fun schedule(delayMillis: Long, task: () -> Unit): AutoCloseable {
        val entry = Task(now + delayMillis, task)
        tasks += entry
        return AutoCloseable { entry.cancelled = true }
    }

    fun advance(millis: Long) {
        val until = now + millis
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.run()
        }
        now = until
    }

    fun pending() = tasks.count { !it.cancelled }
}

internal class RecordingPrompts : HostPrompts {
    class Countdown(val seconds: Int, val onCancel: () -> Unit, val onFinish: () -> Unit) { var closed = false }
    val toasts = ArrayList<Pair<Int, List<Any>>>()
    val confirms = ArrayList<Triple<Int, List<Any>, () -> Unit>>()
    val unavailable = ArrayList<() -> Unit>()
    val countdowns = ArrayList<Countdown>()
    var installs = 0

    override fun install(scope: FeatureScope) { installs++ }
    override fun toast(text: Int, vararg args: Any) { toasts += text to args.toList() }
    override fun confirm(title: Int, message: Int, messageArgs: Array<out Any>, confirm: Int, onUnavailable: () -> Unit, onConfirm: () -> Unit) {
        confirms += Triple(message, messageArgs.toList(), onConfirm)
        unavailable += onUnavailable
    }
    class Choice(val title: Int, val items: List<Any>, val onCancel: () -> Unit, val onChosen: (Int) -> Unit, val onUnavailable: () -> Unit)
    val choices = ArrayList<Choice>()
    override fun choose(title: Int, items: List<Any>, cancel: Int, onUnavailable: () -> Unit, onCancel: () -> Unit, onChosen: (Int) -> Unit) {
        choices += Choice(title, items, onCancel, onChosen, onUnavailable)
    }
    class ManyChoice(val title: Int, val items: List<Any>, val limit: Int, val onChosen: (Set<Int>) -> Unit, val onUnavailable: () -> Unit)
    val manyChoices = ArrayList<ManyChoice>()
    override fun chooseMany(title: Int, items: List<Any>, limit: Int, confirm: Int, cancel: Int, limitMessage: Int, onUnavailable: () -> Unit, onChosen: (Set<Int>) -> Unit) {
        manyChoices += ManyChoice(title, items, limit, onChosen, onUnavailable)
    }
    class Pick(val title: Int, val items: List<Any>, val onCancel: () -> Unit, val onOther: () -> Unit, val onChosen: (Int) -> Unit, val onUnavailable: () -> Unit)
    val picks = ArrayList<Pick>()
    override fun pick(
        title: Int, label: Int, items: List<Any>, confirm: Int, other: Int, cancel: Int,
        onUnavailable: () -> Unit, onCancel: () -> Unit, onOther: () -> Unit, onChosen: (Int) -> Unit,
    ) {
        picks += Pick(title, items, onCancel, onOther, onChosen, onUnavailable)
    }
    class Sheet(val content: ((Int, Array<out Any>) -> String) -> io.github.nuke.oss.app.shared.activity.InfoSheet, val onSave: (String, (Int, Any) -> Unit) -> Unit)
    val sheets = ArrayList<Sheet>()
    override fun sheet(content: ((Int, Array<out Any>) -> String) -> io.github.nuke.oss.app.shared.activity.InfoSheet, onSave: (text: String, done: (message: Int, argument: Any) -> Unit) -> Unit) {
        sheets += Sheet(content, onSave)
    }
    override fun countdown(title: Int, message: Int, seconds: Int, cancel: Int, onCancel: () -> Unit, onFinish: () -> Unit): AutoCloseable {
        val countdown = Countdown(seconds, onCancel, onFinish)
        countdowns += countdown
        return AutoCloseable { countdown.closed = true }
    }
}

class RecordingBars : SystemBars {
    val visible = ArrayList<Boolean>()
    override fun install(scope: FeatureScope, featureId: String) {}
    var transparentNavigation = 0
    override fun setStatusBarVisible(visible: Boolean) { this.visible += visible }
    override fun setNavigationBarTransparent() { transparentNavigation++ }
}

class FakeForeground(var name: String? = null) : Foreground {
    override fun install(scope: FeatureScope, featureId: String) {}
    override fun activityName() = name
}

class RecordingExit : HostExit {
    var exits = 0
    override fun exit() { exits++ }
}

internal fun testServices(
    resolver: DexResolver,
    loader: ClassLoader,
    prompts: HostPrompts = RecordingPrompts(),
    scheduler: TaskScheduler = ManualScheduler(),
) = TikTokFeatureServices(
    resolver, loader, OptionRegistry(MemoryOptionStore())::values, Logger {},
    prompts = { prompts }, systemBars = ::RecordingBars, foreground = ::FakeForeground, exit = RecordingExit(), scheduler = scheduler, memory = MemoryFeatureMemory(),
    covers = { RecordingCovers() },
    cleanScreen = { RecordingCleanScreen() },
    opacityScreen = { RecordingOpacityScreen() },
    dislikeButton = { RecordingDislikeButton() },
    autoPlayButton = { RecordingAutoPlayButton() },
    messageLabels = { RecordingMessageLabels() },
    chatSwipe = { RecordingChatSwipe() },
    repeatMarks = { RecordingRepeatMarks() },
    sharePanelEntries = { RecordingSharePanelEntries() },
    friendPrefixes = { RecordingFriendPrefixes() },
    recallNoticeUi = { RecordingRecallNoticeUi() },
    recallMarks = { RecordingRecallMarks() },
    rewardAdUi = { RecordingRewardAdUi() },
    realNameDialogs = { RecordingRealNameDialogs() },
    pickerViews = { RecordingPickerViews() },
    avatarPreviews = { RecordingAvatarPreviews() },
    locationLabels = { RecordingLocationLabels() },
    textCopier = { RecordingTextCopier() },
    mediaFiles = { RecordingMediaFiles() },
    notificationPermission = { RecordingNotificationPermission() },
    tabMenu = { RecordingTabMenu() },
    authorRow = { RecordingAuthorRow() },
    danmakuMenu = { RecordingDanmakuMenu() },
    commentButtons = { RecordingCommentButtons() },
    commentPanelStyle = { RecordingCommentPanelStyle() },
    commentPages = { RecordingCommentPages() },
    commentDetailsRow = { RecordingCommentDetailsRow() },
    filePicker = { RecordingFilePicker() },
    audioConverter = { FakeAudioConverter() },
    progressLabel = { IgnoredProgressLabel() },
    bottomInfoLabels = { IgnoredBottomInfoLabels() },
    moduleStrings = { _, id -> "text-$id" },
    streakDirectory = java.io.File(System.getProperty("java.io.tmpdir"), "nuke-test-streak"),
    packetTransport = { io.github.nuke.oss.app.tiktok.protocol.PacketTransport { _, _ -> error("No host IM SDK in tests") } },
    featureFiles = java.io.File(System.getProperty("java.io.tmpdir"), "nuke-test-feature-files"),
)

internal class RecordingCovers : io.github.nuke.oss.app.tiktok.feature.feed.CoverDecorator {
    class Call(val view: Any, val createdAt: String?, val stats: List<Pair<io.github.nuke.oss.app.tiktok.feature.feed.CoverStat, String>>, val hideLike: Boolean)
    val calls = ArrayList<Call>()
    override fun decorate(
        itemView: Any, createdAt: String?, stats: List<Pair<io.github.nuke.oss.app.tiktok.feature.feed.CoverStat, String>>, hideLikeCount: Boolean,
    ) { calls += Call(itemView, createdAt, stats, hideLikeCount) }
}

internal class RecordingCleanScreen : io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen {
    var root: io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode? = null
    val hidden = ArrayList<Pair<io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan, Boolean>>()
    var restores = 0
    override fun install(scope: FeatureScope, featureId: String) {}
    override fun snapshot() = root
    override fun hide(plan: io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan, keepClickable: Boolean) { hidden += plan to keepClickable }
    override fun restore() { restores++ }
}

internal class RecordingOpacityScreen : io.github.nuke.oss.app.tiktok.feature.screen.OpacityScreen {
    var root: io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode? = null
    val applied = ArrayList<Pair<List<Any>, Float>>()
    override fun install(scope: FeatureScope, featureId: String) {}
    override fun snapshot() = root
    override fun apply(views: List<Any>, alpha: Float) { applied += views to alpha }
    override fun restore() {}
}

internal class RecordingDislikeButton : io.github.nuke.oss.app.tiktok.feature.feed.DislikeButton {
    class Attached(val bar: Any, val placement: io.github.nuke.oss.app.tiktok.feature.feed.DislikePlacement, val onClick: (Any) -> Unit)
    val attached = ArrayList<Attached>()
    override fun attach(bar: Any, placement: io.github.nuke.oss.app.tiktok.feature.feed.DislikePlacement, onClick: (context: Any) -> Unit) {
        attached += Attached(bar, placement, onClick)
    }
}

internal class RecordingAutoPlayButton : io.github.nuke.oss.app.tiktok.feature.playback.AutoPlayButton {
    class Attached(val bar: Any, val active: () -> Boolean, val onClick: () -> Unit)
    val attached = ArrayList<Attached>()
    val updates = ArrayList<Boolean>()
    override fun attach(bar: Any, active: () -> Boolean, onClick: () -> Unit) {
        attached += Attached(bar, active, onClick)
    }
    override fun update(active: Boolean) { updates += active }
}

internal class RecordingMessageLabels : io.github.nuke.oss.app.tiktok.feature.chat.MessageTimeLabels {
    val prepared = ArrayList<Pair<Any, Int?>>()
    val shown = ArrayList<Triple<Any, String, Boolean>>()
    override fun prepare(row: Any, color: Int?) { prepared += row to color }
    override fun show(row: Any, text: String, mine: Boolean) { shown += Triple(row, text, mine) }
}

internal class RecordingChatSwipe : io.github.nuke.oss.app.tiktok.feature.chat.ChatSwipe {
    val lists = ArrayList<Any>()
    val handlers = ArrayList<(Any) -> Unit>()
    override fun attach(list: Any, onSwipe: (row: Any) -> Unit) { lists += list; handlers += onSwipe }
}

internal class RecordingAuthorRow : io.github.nuke.oss.app.tiktok.feature.feed.AuthorRow {
    val attached = ArrayList<Pair<Any, io.github.nuke.oss.app.tiktok.feature.feed.AuthorInfo>>()
    val full = ArrayList<io.github.nuke.oss.app.tiktok.feature.feed.AuthorFullInfo?>()
    override fun attach(root: Any, info: io.github.nuke.oss.app.tiktok.feature.feed.AuthorInfo, full: io.github.nuke.oss.app.tiktok.feature.feed.AuthorFullInfo?) {
        attached += root to info
        this.full += full
    }
}

internal class RecordingCommentButtons : io.github.nuke.oss.app.tiktok.feature.comment.CommentButtons {
    val hidden = ArrayList<Triple<Any, Boolean, Boolean>>()
    override fun hide(row: Any, like: Boolean, dislike: Boolean) { hidden += Triple(row, like, dislike) }
}

internal class RecordingDanmakuMenu : io.github.nuke.oss.app.tiktok.feature.playback.DanmakuMenu {
    val attached = ArrayList<Pair<Any, (Any) -> Unit>>()
    val copied = ArrayList<String>()
    override fun attach(popover: Any, onClick: (context: Any) -> Unit) { attached += popover to onClick }
    override fun copy(context: Any, text: String) { copied += text }
}

internal class RecordingRepeatMarks : io.github.nuke.oss.app.tiktok.feature.chat.RepeatMarks {
    val prepared = ArrayList<Any>()
    val taps = HashMap<Any, () -> Unit>()
    override fun prepare(row: Any) { prepared += row }
    override fun onTap(row: Any, action: () -> Unit) { taps[row] = action }
}

internal class IgnoredProgressLabel : ProgressLabel {
    override fun attach(seekBar: Any) {}
    override fun show(text: String) {}
}

internal class IgnoredBottomInfoLabels : BottomInfoLabels {
    override fun attach(layout: Any) {}
    override fun show(text: String) {}
}

internal class RecordingTabMenu : io.github.nuke.oss.app.tiktok.feature.chat.TabMenu {
    val marked = ArrayList<Pair<Any, Any>>()
    val shown = ArrayList<List<io.github.nuke.oss.app.tiktok.feature.chat.TabEntry>>()
    var onScreen = true
    var restores = 0
    override fun mark(tab: Any, content: Any) { marked += tab to content }
    override fun tabOf(view: Any): Any? = marked.firstOrNull { it.first === view || it.second === view }?.first?.takeIf { onScreen }
    override fun show(tab: Any, entries: List<io.github.nuke.oss.app.tiktok.feature.chat.TabEntry>): Boolean { shown += entries; return true }
    override fun restore() { restores++ }
}

internal class RecordingNotificationPermission : io.github.nuke.oss.app.tiktok.feature.general.NotificationPermission {
    var pending = true
    val denied = ArrayList<Pair<Any, Int>>()
    override fun isPendingNotificationRequest(activity: Any, permissions: Array<*>) = pending && permissions.toList() == listOf("android.permission.POST_NOTIFICATIONS")
    override fun deny(activity: Any, requestCode: Int) { denied += activity to requestCode }
}

internal class RecordingMediaFiles : io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles {
    class Saved(val urls: List<String>, val audio: Boolean, val folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder)
    val saved = ArrayList<Saved>()
    val converted = ArrayList<java.io.File>()
    var needsPermission = false
    var failure: IllegalStateException? = null
    override fun requestStoragePermission(activity: Any) = needsPermission
    override fun save(context: Any, urls: List<String>, audio: Boolean, folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder) {
        failure?.let { throw it }
        saved += Saved(urls, audio, folder)
    }
    val texts = ArrayList<Pair<String, String>>()
    var textFailure = false
    override fun saveText(context: Any, name: String, text: String): String {
        if (textFailure) throw failure ?: IllegalStateException("The text could not be saved")
        texts += name to text
        return "Downloads/Nuke/$name"
    }
    override fun convertVvic(file: java.io.File) { converted += file }
}

internal class RecordingTextCopier : io.github.nuke.oss.app.tiktok.feature.feed.TextCopier {
    val copied = ArrayList<String>()
    val longClickable = ArrayList<Any>()
    val confirmed = ArrayList<Any>()
    override fun install(scope: FeatureScope, featureId: String) {}
    override fun copy(text: CharSequence?): Boolean { if (text.isNullOrBlank()) return false; copied += text.toString(); return true }
    override fun makeLongClickable(view: Any) { longClickable += view }
    override fun confirmLongPress(view: Any) { confirmed += view }
}

internal class RecordingLocationLabels : io.github.nuke.oss.app.tiktok.feature.feed.LocationLabels {
    val cleared = ArrayList<Any>()
    val decorated = ArrayList<Triple<Any, String, Pair<Int, Int>>>()
    var restores = 0
    override fun clear(root: Any) { cleared += root }
    override fun decorate(root: Any, location: String, startColor: Int, endColor: Int): Boolean { decorated += Triple(root, location, startColor to endColor); return true }
    override fun restoreAll() { restores++ }
}

internal class RecordingAvatarPreviews : io.github.nuke.oss.app.tiktok.feature.feed.AvatarPreviews {
    val bound = HashMap<Any, Pair<Any, () -> Unit>>()
    val touches = ArrayList<Pair<Any, Any>>()
    var cleared = 0
    override fun bind(activity: Any, image: Any, onLongPress: () -> Unit) { bound[activity] = image to onLongPress }
    override fun unbind(activity: Any) { bound.remove(activity) }
    override fun touch(view: Any, event: Any) { touches += view to event }
    override fun clear() { cleared++ }
}

internal class RecordingPickerViews : io.github.nuke.oss.app.tiktok.feature.chat.PickerViews {
    var activity: Any? = null
    var checkbox: Any? = null
    override fun activityOf(view: Any): Any? = activity
    override fun find(root: Any, type: Class<*>): Any? = checkbox?.takeIf(type::isInstance)
}

internal class RecordingRealNameDialogs : io.github.nuke.oss.app.tiktok.feature.miniapp.RealNameDialogs {
    var arguments: io.github.nuke.oss.app.tiktok.feature.miniapp.DialogArguments? = null
    var button: Any? = null
    val pressed = ArrayList<Any>()
    override fun arguments(dialog: Any) = arguments
    override fun closeButton(dialog: Any) = button
    override fun press(button: Any): Boolean { pressed += button; return true }
}

internal class RecordingRewardAdUi : io.github.nuke.oss.app.tiktok.feature.miniapp.RewardAdUi {
    var foreground = true
    override fun isForeground(activity: Any?) = foreground
    override fun emptyJson(): Any = "{}"
    override fun closeJson(): Any = "{close}"
}

internal class RecordingRecallNoticeUi : io.github.nuke.oss.app.tiktok.feature.chat.RecallNoticeUi {
    class Shown(val root: Any, val flare: Boolean) : io.github.nuke.oss.app.tiktok.feature.chat.RecallNotice {
        var removed = false
        var measured = 0
        override fun extendMeasurement(root: Any) { measured++ }
        override fun remove() { removed = true }
    }
    val shown = ArrayList<Shown>()
    var rows: List<Pair<Any, Any>> = emptyList()
    override fun install(scope: FeatureScope, featureId: String) {}
    override fun itemRoot(holder: Any): Any? = holder
    override fun show(root: Any, flare: Boolean): io.github.nuke.oss.app.tiktok.feature.chat.RecallNotice? = Shown(root, flare).also { shown += it }
    override fun visibleRows(recycler: Class<*>, adapter: Class<*>) = rows
}

internal class RecordingSharePanelEntries : io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries {
    val added = ArrayList<io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry>()
    override fun add(scope: FeatureScope, featureId: String, entry: io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry) { added += entry }
}

internal class RecordingFriendPrefixes : io.github.nuke.oss.app.tiktok.feature.sharing.FriendPrefixes {
    class Attached(val list: Any, val groups: List<io.github.nuke.oss.app.tiktok.feature.sharing.PrefixGroup>, val onTap: (Int) -> Unit)
    val attached = ArrayList<Attached>()
    var onScreen = true
    var installs = 0
    override fun install(scope: FeatureScope, featureId: String) { installs++ }
    override fun attach(list: Any, groups: List<io.github.nuke.oss.app.tiktok.feature.sharing.PrefixGroup>, onTap: (index: Int) -> Unit) { attached += Attached(list, groups, onTap) }
    override fun isAttached(list: Any) = onScreen && attached.any { it.list === list }
}

internal class RecordingRecallMarks : io.github.nuke.oss.app.tiktok.feature.chat.RecallMarks {
    val marked = LinkedHashMap<Any, String>()
    override fun mark(row: Any, style: String) { marked[row] = style }
    override fun clear(row: Any) { marked.remove(row) }
}

internal class RecordingCommentPanelStyle : io.github.nuke.oss.app.tiktok.feature.comment.CommentPanelStyle {
    val applied = ArrayList<Triple<Any, Boolean, Int?>>()
    val backgrounds = ArrayList<java.io.File?>()
    override fun apply(page: Any, transparent: Boolean, textColor: Int?, background: java.io.File?) {
        applied += Triple(page, transparent, textColor)
        backgrounds += background
    }
}

internal class RecordingCommentPages : io.github.nuke.oss.app.tiktok.feature.comment.CommentPages {
    val opened = ArrayList<Triple<Any, String, String>>()
    override fun open(activity: Any, awemeId: String, cid: String) { opened += Triple(activity, awemeId, cid) }
}

internal class RecordingCommentDetailsRow : io.github.nuke.oss.app.tiktok.feature.comment.CommentDetailsRow {
    val attached = ArrayList<Pair<Any, List<io.github.nuke.oss.app.tiktok.feature.comment.RowButton>>>()
    override fun attach(panel: Any, buttons: List<io.github.nuke.oss.app.tiktok.feature.comment.RowButton>) { attached += panel to buttons }
}

// Answers each pick with what the test queued, at once and on the calling thread.
internal class RecordingFilePicker : io.github.nuke.oss.app.shared.activity.FilePicker {
    val picks = ArrayList<List<String>>()
    val answers = ArrayDeque<io.github.nuke.oss.app.shared.activity.PickResult>()
    var installs = 0
    override fun install(scope: FeatureScope, featureId: String) { installs++ }
    override fun pick(mimeTypes: List<String>, maxBytes: Long, done: (io.github.nuke.oss.app.shared.activity.PickResult) -> Unit) {
        picks += mimeTypes
        done(answers.removeFirstOrNull() ?: io.github.nuke.oss.app.shared.activity.PickResult.Cancelled)
    }
}

// Copies the input as the "converted" file and reports a fixed length, or fails with the given reason.
internal class FakeAudioConverter(private val failure: String? = null, private val duration: Long = 4_200) : io.github.nuke.oss.app.shared.media.AudioConverter {
    val converted = ArrayList<java.io.File>()
    override fun toM4a(input: java.io.File, output: java.io.File): io.github.nuke.oss.app.shared.media.AudioConversion {
        converted += input
        if (failure != null) return io.github.nuke.oss.app.shared.media.AudioConversion.Failed(failure)
        input.copyTo(output, overwrite = true)
        return io.github.nuke.oss.app.shared.media.AudioConversion.Converted(output, duration)
    }
}
