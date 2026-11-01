package io.github.nuke.oss.app.tiktok.feature

import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.FixedResult
import io.github.nuke.oss.runtime.RuntimeFeature

internal object FixedResultIds {
    const val KEEP_FEED_ON_RETURN = "KeepFeedOnReturn"
    const val KEEP_UNAVAILABLE_VIDEO = "KeepUnavailableVideo"
    const val ALLOW_BACKGROUND_PLAY = "AllowUncopyrightedBackgroundPlay"
    const val HIDE_PROFILE_VISITS = "HideProfileVisits"
    const val LIFT_FORWARD_LIMIT = "LiftForwardLimit"
    const val BLOCK_TYPING_STATUS_UPLOAD = "BlockTypingStatusUpload"
}

// The features that only answer one host method (or one set of them) with a fixed value.
@HookEntry
internal object FixedResultFeatures : TikTokFeature {
    override fun create(services: TikTokFeatureServices): List<RuntimeFeature> = with(services) {
        val boolean = Boolean::class.javaPrimitiveType!!
        listOf(
            // Answering the resume check "handled" keeps the current videos instead of refreshing the feed.
            FixedResult(FixedResultIds.KEEP_FEED_ON_RETURN, resolver, hostClassLoader, FeedQueries.RESUME_GATE, boolean, true),
            // The handlers that skip a video the host has found is no longer public are not run.
            FixedResult(FixedResultIds.KEEP_UNAVAILABLE_VIDEO, resolver, hostClassLoader, FeedQueries.UNAVAILABLE_VIDEO_SKIP, Void.TYPE, null),
            // The play-in-background check is told there is no copyright restriction.
            FixedResult(
                FixedResultIds.ALLOW_BACKGROUND_PLAY, resolver, hostClassLoader, FeedQueries.COPYRIGHT_GATE, boolean, false,
                FixedResult.Mode.REPLACE,
            ),
            // The coroutine that records a profile visit never runs, so the visit is not recorded.
            FixedResult(FixedResultIds.HIDE_PROFILE_VISITS, resolver, hostClassLoader, FeedQueries.PROFILE_VISIT, Any::class.java, null),
            FixedResult(FixedResultIds.LIFT_FORWARD_LIMIT, resolver, hostClassLoader, ChatQueries.FORWARD_LIMIT, Int::class.javaPrimitiveType!!, FORWARD_LIMIT),
            FixedResult(FixedResultIds.BLOCK_TYPING_STATUS_UPLOAD, resolver, hostClassLoader, ChatQueries.TYPING_STATUS, Void.TYPE, null),
        )
    }
}

private const val FORWARD_LIMIT = 1000
