package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DESCRIPTOR_SEPARATOR
import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.annotatedWith
import io.github.nuke.oss.app.shared.dex.joinDescriptors
import io.github.nuke.oss.app.shared.dex.method
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.dex.uniqueClass
import io.github.nuke.oss.app.shared.dex.uniqueMethod
import java.lang.reflect.Modifier
import org.luckypray.dexkit.result.MethodData

private const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"

// Dex queries for the feed, playback and profile behaviors. Each one is located by a string or annotation the
// host keeps for its own logging or settings, and is a stale lookup the moment it stops being unique.
@DexQueries
internal object FeedQueries {
    // The resume check behind the home feed's "refresh on return". It takes the visibility flag and is the only
    // method of that fragment that uses the constant 564.
    val RESUME_GATE = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("feed_recommend_fragment_resume_duration") } }
        check(owners.isNotEmpty()) { "found no feed fragment" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                paramCount = 1
                usingNumbers(564)
            }
        }
    }

    // Handlers taking the smart-client model and the Aweme, which is where the host reacts to a video that is no
    // longer public. Only classes with a single plain constructor qualify.
    val UNAVAILABLE_VIDEO_SKIP = DexQuery { bridge ->
        val model = bridge.uniqueClass { matcher { usingEqStrings(",smartClientType=") } }
        val handlers = bridge.findMethod {
            matcher {
                paramCount = 2
                returnType = "void"
                modifiers(Modifier.PUBLIC or Modifier.FINAL)
                paramTypes(model.name, AWEME)
            }
        }
        joinDescriptors(handlers.filter { it.hasSinglePlainConstructor() })
    }

    // Loads a short drama episode list; its sibling (List, boolean) methods are the ones that receive the items.
    val SHORT_DRAMA_FEED = method {
        matcher { usingStrings("detail_new_load_latest_fix_index", "from_mix_video", "from_mix_detail") }
    }

    // The settings holder for "background play has copyright"; its boolean getter feeds the play-in-background check.
    val COPYRIGHT_GATE = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { annotatedWith("bg_play_have_copyright", element = "value") } }
        check(owners.isNotEmpty()) { "found no settings holder" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                returnType = "boolean"
                paramCount = 0
            }
        }
    }

    // The coroutine that records a visit to another user's profile. Its Kotlin continuation class is named only
    // in debug metadata; older hosts log a failure string from the same code instead.
    val PROFILE_VISIT = DexQuery { bridge ->
        val continuations = bridge.findClass {
            matcher { annotatedWith("com.ss.android.ugc.aweme.profile.panda.network.PandaNetApi\$6\$1") }
        }
        if (continuations.isEmpty()) {
            bridge.uniqueMethod { matcher { usingEqStrings("RecordEnterProfile failed t: ") } }
        } else {
            bridge.uniqueMethod {
                searchInClass(continuations)
                matcher { usingStrings("call to 'resume' before 'invoke' with coroutine") }
            }
        }
    }

    // Decides whether the progress bar is hidden for short videos; it logs the duration it hid the bar for.
    val PROGRESS_BAR_GATE = method {
        matcher { usingStrings("getDefaultState, aweme.duration = ", "shouldHide: true, duration = ") }
    }

    // The home feed panel's two receivers of a video list, found the way the old module does: the refresh path logs
    // "filterRecommendFeedRefreshAweme: deleteAweme, aid =" and the load-more path logs "FullFeedFragmentPanel receive".
    // Both take the incoming videos as their first parameter, before the panel shows them.
    val FEED_RESULTS = DexQuery { bridge ->
        val refresh = bridge.uniqueMethod {
            matcher {
                declaredClass("com.ss.android.ugc.aweme.feed.panel.FullFeedFragmentPanel")
                returnType = "void"
                usingStrings("filterRecommendFeedRefreshAweme: deleteAweme, aid =")
            }
        }
        val loadMore = bridge.uniqueMethod { matcher { usingStrings("FullFeedFragmentPanel receive") } }
        listOf(refresh, loadMore).distinct().joinToString(DESCRIPTOR_SEPARATOR)
    }

    // A static helper that names the kind of a video ("文字", "笔记", ...), next to the string "商品图文".
    val AWEME_KIND = DexQuery { bridge ->
        val owners = bridge.findClass { matcher { usingEqStrings("familiar_slides_animation_duration") } }
        check(owners.isNotEmpty()) { "found no video kind helper" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher { usingEqStrings("商品图文") }
        }
    }

    // The bottom tab bar logs its color changes; the class is the bar View whose background is cleared.
    val BOTTOM_TAB_BAR = method {
        matcher { usingStrings("HsBottomTabVisibility", ", endColor ", "]changeColor: color ") }
    }

    // The main page's bottom spacer under the tab bar sets its own visibility from a value and a reason.
    val BOTTOM_SPACE = method {
        matcher {
            usingStrings("visibility update from ", "MainPage_BottomSpace")
            returnType = "void"
            paramTypes("int", "java.lang.String")
        }
    }

    // Copies a share link: the void (ShareLinkConfig, Context, String, Function1) method of the share link manager,
    // whose String is the short link the host is about to copy with its share text.
    val SHARE_LINK_COPY = DexQuery { bridge ->
        val owners = bridge.findMethod {
            searchPackages("X")
            matcher { usingStrings("ShareLinkManager", "share_extra_params") }
        }.mapNotNull { it.declaredClass }.distinctBy { it.name }
        check(owners.isNotEmpty()) { "found no share link manager" }
        bridge.uniqueMethod {
            searchInClass(owners)
            matcher {
                returnType = "void"
                paramTypes(
                    "com.ss.android.ugc.aweme.share.config.ShareLinkConfig", "android.content.Context",
                    "java.lang.String", "kotlin.jvm.functions.Function1",
                )
            }
        }
    }

    // The player's switch to another video; it logs the Aweme it changes to.
    val AWEME_CHANGED = method {
        matcher {
            usingStrings("onAwemeChanged: from:", ", aweme_id: ")
            paramTypes(AWEME, "java.lang.String")
        }
    }

    // The feed pager settling on a page: setCurrentItemInternal(int item, boolean smooth, boolean always, int velocity).
    val PAGER_SELECT = method {
        matcher {
            usingStrings("mCurItem=", "setCurrentItemInternal()", "onPageSelected()")
            paramTypes("int", "boolean", "boolean", "int")
        }
    }

    // A method of the video engine, which declares the no-argument play and pause the feed player goes through.
    val VIDEO_ENGINE = method {
        matcher { usingStrings("force disable native mal because play type: ", "TTVideoEngine") }
    }

    // The player listener's completion callback.
    val PLAYBACK_COMPLETION = method {
        matcher {
            usingStrings("receive onCompletion,this:")
            paramCount = 1
        }
    }

    // The player's progress listener: the void method of the class that logs the open-media error rate, taking the
    // video ID, position and duration in one of two orders.
    val PLAY_PROGRESS = DexQuery { bridge ->
        val owners = bridge.findMethod { matcher { usingEqStrings("aweme_screen_ad_open_media_error_rate", "feed_video_duration") } }
            .mapNotNull { it.declaredClass }.distinctBy { it.name }
        check(owners.isNotEmpty()) { "found no progress listener" }
        val found = PROGRESS_SHAPES.flatMap { shape ->
            bridge.findMethod {
                searchInClass(owners)
                matcher {
                    returnType = "void"
                    paramTypes(*shape)
                }
            }
        }
        check(found.size == 1) { "expected one progress method, found ${found.size}" }
        found.single().descriptor
    }

    // The feed's per-tick chapter bookkeeping: a seven-parameter method that logs the chapter impression and the
    // automatic skip of marketing chapters. It receives the video, the position in milliseconds and the player.
    val CHAPTER_PROGRESS = method {
        matcher {
            usingStrings("chapter_sequence_num_impr", "is_landscape_status", "enter_auto_skip_marketing_chapter")
            paramCount = 7
        }
    }

    // The profile page's entry takes the User it is about to show; it logs the enterprise-homepage entry next to the
    // follow status it was opened with.
    val PROFILE_ENTRY = method {
        matcher { usingStrings("enter_enterprise_homepage", "target_user_origin_follow_status") }
    }

    // The profile page's header ("PandaHeader"): the method that returns the header's own name. The class binds the
    // user in void methods that take a User, and keeps the header's root View and info area in its fields.
    val AUTHOR_HOME = method(version = 2) {
        matcher {
            usingEqStrings("PandaHeader")
            returnType = "java.lang.String"
            paramCount = 0
        }
    }

    // The danmaku bubble's action handler: the method that logs the impression next to the like and delete labels. It
    // takes the danmaku, whose class prints itself as DanmakuStruct(danmakuId=...).
    val DANMAKU_ACTION = method {
        matcher { usingEqStrings("danmaku_inter_impression", "喜欢", "删除") }
    }
    val DANMAKU_STRUCT = method {
        matcher { usingStrings("DanmakuStruct(danmakuId=") }
    }

    // The danmaku style config prints itself as DDanmakuConfig; its constructor's second argument is the text color.
    val DANMAKU_STYLE = method {
        matcher { usingStrings("DDanmakuConfig(alpha=", ", textColor=") }
    }

    // The profile grid's cover holder binds a video to a cover; its class logs the corner label it draws on it, in more
    // than one method, so only the class they share is meaningful.
    val COVER_HOLDER = methods(version = 2) {
        matcher { usingEqStrings("right_bottom_label_token", "right_bottom_label_str") }
    }

    // The quick share button under the video is driven by a presenter that logs its name; its overloads show the button.
    val QUICK_SHARE = method {
        matcher { usingEqStrings("FeedGuideShareBtnBizPresenter , ") }
    }

    // The feed's component lifecycle step logs the event it handles; it takes the component data action and a
    // continuation, and the action carries the set of components to show.
    val COMPONENT_LIFECYCLE = method {
        matcher {
            usingStrings("] handleLifecycleEvent: ")
            paramCount = 2
        }
    }

    // A chat emoji entry and the response that lists the emoji; the latter's getters return the entries.
    val EMOJI_ENTRY = method {
        matcher { usingStrings("OnlineSmallEmoji={uri=", ", displayName=") }
    }
    val EMOJI_RESPONSE = method {
        matcher { usingStrings("OnlineSmallEmojiResInfo{md5=", ", resDirPath=") }
    }

    // The comment-copy path that fetches the clipboard text; its only String getter is the copied text.
    val COMMENT_COPY = method {
        matcher { usingStrings("bpea-clipboard_for_comment_copy", "BPEAException: code=", "support_read_clipboard") }
    }

    // The simplified player sets its playback speed through the one void(float) method that is not its seek method;
    // the seek method is the one that logs "seekTo() progress:".
    val PLAYER_SPEED = DexQuery { bridge ->
        val player = bridge.uniqueClass { matcher { usingStrings("SimplifyAsyncPlayerV3", "nextPrepare()") } }
        val seeks = bridge.findMethod {
            searchInClass(listOf(player))
            matcher { usingStrings("seekTo() progress:") }
        }.map { it.name }.toSet()
        check(seeks.isNotEmpty()) { "found no seek method in ${player.name}" }
        val setters = bridge.findMethod {
            searchInClass(listOf(player))
            matcher {
                returnType = "void"
                paramTypes("float")
            }
        }.filter { it.name !in seeks }
        check(setters.size == 1) { "expected one speed setter, found ${setters.size}" }
        setters.single().descriptor
    }

    // The same player's methods that take the prepare data of a video; the data's toString names the video's ID.
    val PLAYER_PREPARED = DexQuery { bridge ->
        val player = bridge.uniqueClass { matcher { usingStrings("SimplifyAsyncPlayerV3", "nextPrepare()") } }
        val data = bridge.uniqueClass { matcher { usingStrings("PrepareData{urlSupplier=") } }
        joinDescriptors(bridge.findMethod {
            searchInClass(listOf(player))
            matcher {
                returnType = "void"
                paramTypes(data.name)
            }
        })
    }

    // The "not interested" action of the feed: the method that logs the first-level dislike type. Its class builds
    // the action from a share package, which the class around the share-link strings builds.
    val DISLIKE_ACTION = method {
        matcher { usingEqStrings("is_first_level_not_interesting_action", "dislike_type", "common_interest_follow_card") }
    }
    val SHARE_PACKAGE = method {
        matcher { usingEqStrings("ecom_share_track_params", "UrlShorter", "thumb_url_for_share", "sslocal://aweme/detail/") }
    }
}

private fun MethodData.hasSinglePlainConstructor(): Boolean {
    val owner = declaredClass ?: return false
    val constructors = owner.methods.filter { it.isConstructor }
    return constructors.count { Modifier.isPublic(it.modifiers) } == 1 && constructors.all { it.paramCount == 0 }
}

private val PROGRESS_SHAPES = listOf(arrayOf("java.lang.String", "long", "long"), arrayOf("long", "long", "java.lang.String"))
