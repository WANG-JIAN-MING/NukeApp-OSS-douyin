package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.joinDescriptors
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier

// Dex queries for the features whose behavior the user tunes with options.
@DexQueries
internal object OptionQueries {
    // The builders of the home screen's tab id lists. The server data source has one for the top strip and one for the
    // bottom bar, both returning the ids in display order.
    val TAB_LISTS = methods {
        matcher { usingStrings("buildShowingTopTabIds: ", "HomeTabDataSourceServer") }
    }

    // Prepares the player for a video; its parameter is the prepare data that carries the playback speed.
    val PLAYER_PREPARE = method {
        matcher { usingStrings("PrepareData-NULL", "simplify_player_prepare", "simplayer_prepare") }
    }

    // The offline cache page reads how many videos it may keep from an int getter next to the "cache_count" string.
    val CACHE_COUNT = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("offline_use_data_dialog_checkbox") } }
        check(owners.isNotEmpty()) { "found no offline cache page" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                returnType = "int"
                paramCount = 0
                usingEqStrings("cache_count")
            }
        }
    }

    // The profile page's tab set: it receives the user and the list of tab types it is about to show.
    val PROFILE_TABS = method {
        matcher {
            usingEqStrings("replaceFragments tabType:")
            paramCount = 2
        }
    }

    // The ring around a friend's avatar is set up by the presenter that logs "tryPreload"; its onSyncBind draws it.
    val DAILY_RING = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingStrings("tryPreload isLoading, do nothing", "ColorRingPresenter", "tryPreload") } }
        check(owners.isNotEmpty()) { "found no ring presenter" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher { usingStrings(" onSyncBind ") }
        }
    }

    // The avatar share icon setup, which shows the live ring when the author is in a room.
    val LIVE_RING = method {
        matcher {
            usingStrings("setupAvatarShareIcon：followStatus=", ",mPageType=", ",showLive=")
            paramCount = 0
            returnType = "void"
        }
    }

    // The long-press panel's configuration result, a data class named by its toString. The host ships two identical
    // copies, one for the legacy panel builder and one for the config-driven one, and either may be in use.
    val LONG_PRESS_CONFIG = methods(version = 2) {
        matcher { usingStrings("ConfigModuleRepoResult(resultType=", ", modules=", ", dataVersion=") }
    }

    // Applies a danmaku configuration; its sibling methods taking the configuration bean receive the block words. The
    // string is also used by the getter next to it, which returns the bean instead of taking it.
    val DANMAKU_CONFIG = method(version = 2) {
        matcher {
            usingEqStrings("danmaku_config_")
            returnType = "void"
            paramCount = 1
        }
    }

    // Re-applies the display configuration when the main screen is recreated for a font mode change; the class also
    // has the (Resources, Context) method that carries the configuration.
    val DISPLAY_CONFIG = method {
        matcher {
            usingStrings(
                "FontModeHotSwitch_landingTabByRecreateMainActivity",
                "com.ss.android.ugc.aweme.intent.extra.EXTRA_AWEME_PUSH_TAB", "navigation_notification",
            )
        }
    }

    // The static helpers that shorten a count to "1.2w" or "1.2万" for likes, comments, shares and follower numbers.
    // Current hosts have one per unit spelling; both are hooked, and only finding neither is a stale lookup.
    val COUNT_FORMATTERS = DexQuery { bridge ->
        val found = COUNT_UNITS.flatMap { units ->
            bridge.findMethod {
                matcher {
                    modifiers(Modifier.PUBLIC or Modifier.STATIC)
                    paramTypes("long")
                    returnType = "java.lang.String"
                    usingEqStrings(*units.toTypedArray())
                }
            }
        }
        joinDescriptors(found.distinctBy { it.descriptor })
    }

    // The live room audience widget: one method takes the room model with its rounded viewer texts, the other the
    // new viewer count with its two texts and a hidden flag.
    val AUDIENCE_STATS = method {
        matcher {
            usingStrings("LiveRoomAudienceWidget", "this room enableContributor  : ")
            paramCount = 1
        }
    }
    val AUDIENCE_COUNT = method {
        matcher {
            usingStrings("LiveRoomAudienceWidget", "onNewUserCountAdapter  isHidden : ")
            paramTypes("long", "java.lang.String", "java.lang.String", "boolean")
        }
    }

    // Sizes the full-screen feed video; every overload of this name takes the height and width first.
    val VIDEO_RESIZE = method {
        matcher { usingStrings("FeedAllScreenHelper.resizeView") }
    }

    // The share panel's row of share actions takes its items in a void (List) method. The adapter is the class whose
    // view holder logs "ShareFunctionViewHolder"; when that does not lead to one such method, the list arrives in
    // the one void (List) method of the class that logs the item click interception instead. Current hosts name that
    // method with an obfuscated identifier, so it is selected by its signature.
    val SHARE_ACTIONS = DexQuery(version = 2) { bridge ->
        val adapters = bridge.findMethod {
            matcher {
                usingStrings("ShareFunctionViewHolder")
                paramCount = 2
                returnType = "void"
            }
        }.mapNotNull { it.declaredClass }.distinctBy { it.name }
        val receivers = if (adapters.size == 1) {
            bridge.findMethod {
                searchInClass(adapters)
                matcher {
                    returnType = "void"
                    paramTypes("java.util.List")
                }
            }
        } else emptyList()
        receivers.singleOrNull()?.descriptor ?: run {
            val clicks = bridge.findMethod { matcher { usingEqStrings("im_add_share_panel_no_click_ab", "onInterceptItemClick key=") } }
                .mapNotNull { it.declaredClass }.distinctBy { it.name }
            check(clicks.isNotEmpty()) { "found neither share action adapter" }
            bridge.uniqueMethod {
                searchInClass(clicks)
                matcher {
                    returnType = "void"
                    paramTypes("java.util.List")
                }
            }
        }
    }

    // The feed adapter logs each data change; after it the adapter's items are the videos about to be shown.
    val FEED_ADAPTER_CHANGED = method {
        matcher { usingStrings(" notifyDataSetChanged() begin eventType: ", " pageType ", "AbstractFeedAdapter") }
    }

    // The coroutine step that receives a fetched feed page, in the class that logs an empty result.
    val FEED_RESULT = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("feed_items_is_null_or_empty, result = ") } }
        check(owners.isNotEmpty()) { "found no feed result step" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher { usingEqStrings("call to 'resume' before 'invoke' with coroutine") }
        }
    }

    // The plugin loader's step that loads one hot-update plugin by package name; it logs each stage with the package.
    val PLUGIN_LOAD = method {
        matcher {
            usingStrings(
                "PluginLoader modifyStatusFromMainProcess, RESOLVED, pkg = ", "PluginLoader loadPlugin, mira not init, pkg = ",
                "PluginLoader loadFinished, ",
            )
        }
    }

    // Douyin's sleep timer task: the method that starts it logs why a start did not take effect.
    val COUNTDOWN_TASK = method {
        matcher {
            usingStrings("startCountDownTask 实验控制功能不生效", "startCountDownTask 未拉到配置，定时关闭功能无法启动", "startCountDownTask 任务时间无效 ")
        }
    }
}

private val COUNT_UNITS = listOf(listOf("0", "亿", "w"), listOf("0", "亿", "万"))
