package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method

private const val PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"
private const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
private const val FEED_VIEW_HOLDER = "com.ss.android.ugc.aweme.feed.adapter.IFeedViewHolder"
private const val FEED_AVATAR_PRESENTER = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedAvatarPresenter"

@DexQueries
internal object TikTokQueries {
    val DOUBLE_DIGG = method {
        matcher {
            declaredClass = PANEL
            returnType = "void"
            paramTypes("android.view.MotionEvent", FEED_VIEW_HOLDER, AWEME)
        }
    }

    val DOUBLE_TAP = method {
        matcher {
            declaredClass = PANEL
            returnType = "void"
            paramTypes("android.view.MotionEvent")
        }
    }

    val PAUSE = method {
        matcher {
            declaredClass = PANEL
            returnType = "void"
            paramTypes("boolean")
            usingStrings("handlePause")
        }
    }

    val VIDEO_EVENT = method {
        matcher {
            declaredClass = PANEL
            returnType = "void"
            paramCount = 1
            usingEqStrings("handleVideoEvent")
        }
    }

    val PLAYER_EVENT = method {
        matcher {
            declaredClass = PANEL
            name = "onVideoPlayerEvent"
            returnType = "void"
            paramCount = 1
        }
    }

    val CURRENT_AWEME = method {
        matcher {
            declaredClass = PANEL
            name = "getCurrentAweme"
            returnType = AWEME
            paramCount = 0
        }
    }

    val AVATAR_FOLLOW = method {
        matcher {
            declaredClass = FEED_AVATAR_PRESENTER
            returnType = "void"
            paramTypes("int", "boolean")
            usingStrings("updateFollowView")
        }
    }
}
