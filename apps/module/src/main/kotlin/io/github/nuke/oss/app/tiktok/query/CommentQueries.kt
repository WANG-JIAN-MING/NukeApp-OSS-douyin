package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.DESCRIPTOR_SEPARATOR
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.joinDescriptors
import io.github.nuke.oss.app.shared.dex.method

private const val COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment"
private const val SERIALIZED = "com.google.gson.annotations.SerializedName"

// Dex queries for the comment panel. The panel has an older and a newer implementation and the host switches between
// them with an experiment, so a feature that touches both looks for every anchor and uses the ones the host has.
@DexQueries
internal object CommentQueries {
    // The steps that receive a fetched page of comments (a CommentItemList): the older fetch model's two handlers, the
    // newer list data source and its reply-list handler. At least one has to exist.
    val LOADED_PAGES = DexQuery { bridge ->
        val found = listOf(
            bridge.findMethod {
                matcher {
                    usingStrings("CommentFetchModel new data is empty", "isNewDataEmpty", "CommentFetchModel CommentItemList response in handle data")
                    paramCount = 2
                }
            },
            bridge.findMethod {
                matcher {
                    usingStrings("CommentFetchModel new data is empty ", "commentlog_panel_show_list")
                    paramCount = 2
                }
            },
            bridge.findMethod { matcher { usingStrings("#addCommentForComment#: requestType=", ", dataSize=", "CommentListDataSource") } },
            bridge.findMethod { matcher { usingStrings("Reply list response is null. cid=", "#fetchReplyList#: cursor=", ", resultSize=") } },
        ).flatten().distinctBy { it.descriptor }
        joinDescriptors(found)
    }

    // The steps that show a comment's location label: every method outside Comment that takes a Comment and reads its
    // ip_label through the getter. The cell of the newer panel, the older panel's IP component and the comment header
    // each read it on their own, so a feature that marks the label has to cover all of them.
    val COMMENT_IP_READERS = DexQuery { bridge ->
        val getter = bridge.findMethod {
            matcher {
                declaredClass = COMMENT
                returnType = "java.lang.String"
                paramCount = 0
                usingFields { add { field { annotations { add { type = SERIALIZED; usingEqStrings("ip_label") } } } } }
            }
        }.also { check(it.size == 1) { "expected one ip_label getter, found ${it.size}" } }.single()
        val readers = bridge.findMethod {
            matcher {
                invokeMethods { add { declaredClass = COMMENT; name = getter.methodName; returnType = "java.lang.String"; paramCount = 0 } }
            }
        }.filter { it.className != COMMENT && it.paramTypeNames.contains(COMMENT) }
        joinDescriptors(readers)
    }

    // The comment panel's automatic keyboard on a video with no comments. The older panel has a step that takes the
    // comment count; the newer one has a step that takes a keyboard request and logs the two click sources. Each
    // descriptor is tagged with the panel it belongs to, "old:" or "new:". At least one must exist.
    val EMPTY_INPUT = DexQuery(version = 1) { bridge ->
        val old = bridge.findMethod {
            matcher { usingEqStrings("zero_enter_comment_board", "discussion_forum", "hotspot_discussion") }
        }.map { "old:" + it.descriptor }
        val owners = bridge.findClass { matcher { usingEqStrings("zero_enter_comment_board") } }
        val new = if (owners.isEmpty()) emptyList() else bridge.findMethod {
            searchInClass(owners)
            matcher { usingEqStrings("click_emoji_icon", "click_text_bar") }
        }.map { "new:" + it.descriptor }
        val found = old + new
        check(found.isNotEmpty()) { "matched no keyboard step" }
        found.joinToString(DESCRIPTOR_SEPARATOR)
    }

    // The newer panel's like and dislike buttons under a comment are built from a UI state class that logs its name.
    val BUTTONS_STATE = method {
        matcher { usingStrings("KCmtCellInteractiveButtonsUiState") }
    }

    // The older panel's comment adapter, which logs whether it shows a footer.
    val COMMENT_ADAPTER = method {
        matcher { usingEqStrings("showFooter: false", "showFooter: true") }
    }

    // The long-press menu of a comment: the provider logs the panel it shows, and its same-named methods build the
    // item groups.
    val LONG_PRESS_PANEL = method {
        matcher { usingStrings("Comment long click panel show: {", "CommentLongPressItemProvider") }
    }

    // The "related search" strip under the video in the comment panel: a void method of three parameters (the strip's
    // container, the video and a third object) that logs the strip's view tags.
    val RELATED_SEARCH = method {
        matcher {
            usingStrings("tag_comment_rs_container_view", "tag_comment_rs_view", "comment_related_search")
            paramCount = 3
        }
    }

    // Formats a comment's time relative to now ("刚刚", "3分钟前", "昨天" ...) for the new comment panel; its first long
    // parameter is the time in milliseconds.
    val RELATIVE_TIME = method {
        matcher {
            usingEqStrings("分钟前", "刚刚", "小时前", "昨天", "天前")
            paramCount = 3
            returnType = "java.lang.String"
        }
    }

    // The publish request's success step; it logs the publish and the comment board. Its first parameter is the server
    // response, which holds the published Comment in its comment field.
    val PUBLISH_SUCCESS = method {
        matcher {
            usingStrings("publish onSuccess", "comment_board")
            paramCount = 2
        }
    }

    // Reads the web session cookie the host keeps for its own web requests (the feed cookie store).
    val WEB_COOKIE = method {
        matcher {
            usingStrings("feed_cookie_key")
            paramCount = 0
            returnType = "java.lang.String"
        }
    }

    // The comment list page; it logs when it is reset for another post. Its class builds the page's View in onCreateView.
    val LIST_PAGE = method {
        matcher { usingStrings("CommentListPageFragment: resetPageParam() => differentAweme = ", "search_show_comment_style") }
    }

    // The comment panel's pager fragment; it asks, for each page, whether the page is shown.
    val PANEL_TABS = method {
        matcher { usingStrings("CommentListPageFragment ", "ViewPager.scrollX: ", "mFragmentLists.size: ") }
    }

    // The recorded voice of a comment prints itself as AudioModel(localFile=, waveList=, ...); its class holds the file.
    val AUDIO_MODEL = method {
        matcher { usingStrings("AudioModel(localFile=", ", waveList=") }
    }

    // The step that publishes a comment; it logs the publish scene next to the reply and at-mention strings. Its first
    // parameter is the publish request, which holds the comment text and its list of text extras.
    val COMMENT_SEND = method {
        matcher { usingStrings("comment_at_when_publish", "comment_replay_non_author", "comment_replay_author", "PUBLISH_COMMENT") }
    }
}
