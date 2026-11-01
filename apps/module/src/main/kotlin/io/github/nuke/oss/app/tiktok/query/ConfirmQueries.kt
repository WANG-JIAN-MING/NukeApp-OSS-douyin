package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.uniqueMethod

// Dex queries for the taps that can ask for a confirmation first. Each is located by strings the host keeps for its own
// logging or analytics events, and is a stale lookup the moment it stops being unique.
@DexQueries
internal object ConfirmQueries {
    // The favourite button's click handler; it logs the click and the allow check, and takes two callbacks.
    val COLLECT = method {
        matcher { usingStrings("clickView", "checker.checkAllow ") }
    }

    // The follow action of a feed video; it takes the user ID.
    val FOLLOW = method {
        matcher { usingStrings("无网络，操作失败", "feed_internal_event", "follow_from_type_pre", "follow") }
    }

    // The like button's click listener class: it logs the offline notice next to the "click_like" event.
    val LIKE = method {
        matcher { usingEqStrings("无网络，操作失败", "click_like", "offline_mode", "homepage_fresh", "当前无网络，暂不可用") }
    }

    // Tapping a friend's avatar ring opens their daily; the presenter that preloads it names the click handler.
    val DAILY = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("tryPreload isLoading, do nothing") } }
        check(owners.isNotEmpty()) { "found no daily presenter" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                paramCount = 1
                usingEqStrings("animation_type")
            }
        }
    }

    // Tapping an avatar whose author is live opens the live room instead of the profile.
    val LIVE_AVATAR = method {
        matcher { usingStrings("不在直播", "FeedLiveAvatarPresenter_", "interceptTurnProfile") }
    }

    val FULL_SCREEN = method {
        matcher { usingStrings("click_landscape_mode_entrance", "app_rotate_status_source") }
    }

    val TOPIC = method {
        matcher { usingStrings("entrance_video_title_entity", "extra_challenge_is_hashtag", "challenge_click") }
    }

    val LIVE_COVER = method {
        matcher { usingStrings("LivePluginEnterGuideHandler_", "isClickWhenPluginInstall") }
    }

    val SEARCH_PAGE = method {
        matcher { usingStrings("SearchPageLauncher", "search_entrance_style", "homepage_author_id", "launch failed") }
    }

    // The like of a comment. The newer comment panel keeps it in the digg-and-bury cell package, the older one in a
    // two-parameter method that logs "like" next to the poi comment click, or failing that in the class around the
    // comment-management strings. The first of these that is unique is used.
    val COMMENT_LIKE = DexQuery { bridge ->
        val newer = bridge.findMethod {
            searchPackages("com.douyin.kmp.comment.comment.commentpanellist.cell.interactive.diggbury")
            matcher { usingEqStrings("enterFrom", "cid", "isKmp", " : ") }
        }
        val older = bridge.findMethod {
            matcher {
                usingEqStrings("like", "click_poi_comment_like")
                paramCount = 2
            }
        }
        val fallbackOwners = if (older.isEmpty()) bridge.findClass { matcher { usingEqStrings("comment_mgt_people", "previous_choice") } } else emptyList()
        val fallback = if (fallbackOwners.isEmpty()) emptyList() else bridge.findMethod {
            searchInClass(fallbackOwners)
            matcher {
                usingEqStrings("like")
                paramCount = 2
            }
        }
        val found = listOf(newer, older, fallback).firstOrNull { it.size == 1 }
        checkNotNull(found) { "expected one comment like, found ${newer.size}/${older.size}/${fallback.size}" }
        found.single().descriptor
    }
}
