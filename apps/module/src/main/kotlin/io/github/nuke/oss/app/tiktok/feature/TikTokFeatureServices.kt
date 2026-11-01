package io.github.nuke.oss.app.tiktok.feature

import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.app.shared.activity.FilePicker
import io.github.nuke.oss.app.shared.activity.Foreground
import io.github.nuke.oss.app.shared.media.AudioConverter
import io.github.nuke.oss.app.shared.activity.HostExit
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.SystemBars
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.registry.FeatureEntry
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.shared.settings.Option
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.feature.chat.ChatSwipe
import io.github.nuke.oss.app.tiktok.feature.chat.MessageTimeLabels
import io.github.nuke.oss.app.tiktok.feature.chat.RepeatMarks
import io.github.nuke.oss.app.tiktok.feature.sharing.FriendPrefixes
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.chat.RecallNoticeUi
import io.github.nuke.oss.app.tiktok.feature.chat.RecallMarks
import io.github.nuke.oss.app.tiktok.feature.miniapp.RewardAdUi
import io.github.nuke.oss.app.tiktok.feature.miniapp.RealNameDialogs
import io.github.nuke.oss.app.tiktok.feature.chat.PickerViews
import io.github.nuke.oss.app.tiktok.feature.feed.AvatarPreviews
import io.github.nuke.oss.app.tiktok.feature.feed.LocationLabels
import io.github.nuke.oss.app.tiktok.feature.feed.TextCopier
import io.github.nuke.oss.app.tiktok.feature.general.NotificationPermission
import io.github.nuke.oss.app.tiktok.feature.chat.TabMenu
import io.github.nuke.oss.app.tiktok.feature.comment.CommentButtons
import io.github.nuke.oss.app.tiktok.feature.comment.CommentPanelStyle
import io.github.nuke.oss.app.tiktok.feature.comment.CommentPages
import io.github.nuke.oss.app.tiktok.feature.comment.CommentDetailsRow
import io.github.nuke.oss.app.tiktok.feature.feed.AuthorRow
import io.github.nuke.oss.app.tiktok.feature.feed.BottomInfoLabels
import io.github.nuke.oss.app.tiktok.feature.feed.CoverDecorator
import io.github.nuke.oss.app.tiktok.feature.feed.DislikeButton
import io.github.nuke.oss.app.tiktok.feature.playback.AutoPlayButton
import io.github.nuke.oss.app.tiktok.feature.playback.DanmakuMenu
import io.github.nuke.oss.app.tiktok.feature.playback.ProgressLabel
import io.github.nuke.oss.app.tiktok.feature.screen.OpacityScreen
import io.github.nuke.oss.app.tiktok.protocol.PacketTransport
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.core.Logger
import java.io.File

// What every TikTok feature entry builds its features from; the adapter makes one per launch, so events and chatRows
// are shared by all features. Features with options take their values from options, which reads them from storage
// and records them for the settings screen. The UI pieces are factories so each feature gets its own, installed in
// its own scope.
internal class TikTokFeatureServices(
    val resolver: DexResolver,
    val hostClassLoader: ClassLoader,
    val options: (featureId: String, List<Option>) -> OptionValues,
    val logger: Logger,
    val prompts: (featureId: String) -> HostPrompts,
    val systemBars: () -> SystemBars,
    val foreground: () -> Foreground,
    val exit: HostExit,
    val scheduler: TaskScheduler,
    val memory: FeatureMemory,
    val covers: () -> CoverDecorator,
    val cleanScreen: () -> CleanScreen,
    val opacityScreen: () -> OpacityScreen,
    val dislikeButton: () -> DislikeButton,
    val autoPlayButton: () -> AutoPlayButton,
    val messageLabels: () -> MessageTimeLabels,
    val chatSwipe: () -> ChatSwipe,
    val repeatMarks: () -> RepeatMarks,
    val sharePanelEntries: () -> SharePanelEntries,
    val friendPrefixes: () -> FriendPrefixes,
    val recallNoticeUi: () -> RecallNoticeUi,
    val recallMarks: () -> RecallMarks,
    val rewardAdUi: () -> RewardAdUi,
    val realNameDialogs: () -> RealNameDialogs,
    val pickerViews: () -> PickerViews,
    val avatarPreviews: () -> AvatarPreviews,
    val locationLabels: () -> LocationLabels,
    val textCopier: () -> TextCopier,
    val mediaFiles: () -> MediaFiles,
    val notificationPermission: () -> NotificationPermission,
    val tabMenu: () -> TabMenu,
    val moduleStrings: ModuleStrings,
    val authorRow: () -> AuthorRow,
    val danmakuMenu: () -> DanmakuMenu,
    val commentButtons: () -> CommentButtons,
    val commentPanelStyle: () -> CommentPanelStyle,
    val commentPages: () -> CommentPages,
    val commentDetailsRow: () -> CommentDetailsRow,
    // A system file picker whose copies go to a private directory of that name.
    val filePicker: (name: String) -> FilePicker,
    val audioConverter: () -> AudioConverter,
    val progressLabel: () -> ProgressLabel,
    val bottomInfoLabels: () -> BottomInfoLabels,
    val streakDirectory: File,
    // The host's IM SDK raw request path; built lazily, since only the protocol sender uses it.
    val packetTransport: () -> PacketTransport,
    // Files a feature's settings keep in the host's private storage, one subdirectory per feature ID.
    val featureFiles: File,
    // The kind of host process the features are built in; most only ever run in the main one.
    val process: HostProcess = HostProcess.MAIN,
) {
    val events = FeedEvents(resolver, hostClassLoader)
    val chatRows = ChatAdapterEvents(hostClassLoader)
}

// A TikTok @HookEntry: the generated generatedHookEntries lists every one of them.
internal typealias TikTokFeature = FeatureEntry<TikTokFeatureServices>
