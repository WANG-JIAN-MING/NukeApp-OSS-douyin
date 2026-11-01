package io.github.nuke.oss.app.tiktok.sdk.screen

// A snapshot of one View in the screen in front: just what the clean mode rules look at, so they can be run and tested
// without a device. handle is the View itself, given back when the plan is carried out.
internal class CleanNode(
    val simpleName: String,
    val className: String,
    val text: String?,
    val hint: String?,
    val kind: Kind,
    val handle: Any,
    // The View's ID resource name, when it has one.
    val idName: String? = null,
) {
    enum class Kind { LINEAR, FRAME, RELATIVE, CONSTRAINT, SEEK_BAR, TEXT, EDIT_TEXT, OTHER }

    var parent: CleanNode? = null
        private set
    val children = ArrayList<CleanNode>()

    fun add(child: CleanNode): CleanNode { child.parent = this; children += child; return this }

    // Every View below this one, parents before their children.
    fun descendants(): List<CleanNode> {
        val out = ArrayList<CleanNode>()
        fun walk(node: CleanNode) { for (child in node.children) { out += child; walk(child) } }
        walk(this)
        return out
    }
}

internal data class CleanOptions(
    val keepSeekBar: Boolean,
    val hideDanmaku: Boolean,
    val keepVideoInfo: Boolean,
)

// What to do with the screen: fade views are hidden and brought back with their old look, gone views are only hidden.
internal class CleanPlan(val fade: List<Any>, val gone: List<Any>)

// The rules, in order: the first one that claims a View decides it. Views no rule claims are hidden only
// if they are a layout or seek bar of one of the known container classes.
internal fun planCleanScreen(root: CleanNode, options: CleanOptions): CleanPlan {
    val fade = ArrayList<CleanNode>()
    val gone = ArrayList<CleanNode>()
    val containers = ArrayList<CleanNode>()
    for (view in root.descendants()) {
        if (!claimed(view, options, fade, gone)) {
            if (view.kind == CleanNode.Kind.LINEAR || view.kind == CleanNode.Kind.FRAME || view.kind == CleanNode.Kind.SEEK_BAR) containers += view
        }
    }
    for (view in containers) if (view.simpleName in CONTAINER_NAMES) fade += view
    return CleanPlan(fade.map { it.handle }, gone.map { it.handle })
}

private fun claimed(view: CleanNode, options: CleanOptions, fade: MutableList<CleanNode>, gone: MutableList<CleanNode>): Boolean {
    when {
        // Controls on the right edge of the player, and the sound disc.
        view.simpleName == "MusicCoverContainerLayout" -> { gone += view; return true }
        view.simpleName == "FpsControlFrameLayout" -> {
            gone += view.descendants().filter { it.kind == CleanNode.Kind.RELATIVE }
            return true
        }
        // The danmaku layer.
        (view.simpleName == "DanmakuView" || view.simpleName == "DDanmakuComposeView") && options.hideDanmaku -> { fade += view; return true }
        // The progress bar, unless it is to stay.
        (view.simpleName == "ProSeekBarView" || view.simpleName == "CustomizedUISeekBar" || view.kind == CleanNode.Kind.SEEK_BAR) &&
            !options.keepSeekBar -> { fade += view; return true }
        // The feedback and full-screen entries are text; the whole entry is the text's parent.
        view.kind == CleanNode.Kind.TEXT && view.text == "反馈" -> { view.parent?.let { fade += it }; return true }
        view.kind == CleanNode.Kind.TEXT && view.text == "全屏观看" -> { view.parent?.let { fade += it }; return true }
    }
    if (view.className == "androidx.constraintlayout.widget.ConstraintLayout" && claimsSearchBar(view)) { fade += view; return true }
    when (view.simpleName) {
        "MainBottomTabContainer", "MainTitleBar", "MainTabStripNew" -> { gone += view; return true }
        "AwemeIntroInfoLayout", "MeasureOnceCheckDrawChildFrameLayout" -> {
            if (!options.keepVideoInfo) fade += view
            return true
        }
        "HashTagInteractStickerViewNew" -> { fade += view; return true }
    }
    if (view.simpleName == "QLayout" && view.kind == CleanNode.Kind.RELATIVE && claimsMessageEntry(view)) { fade += view; return true }
    return false
}

// A bar with a search hint (longer than three characters) or a "search" / "forward to daily" label. The first input or
// label that is not ready to read (no hint, no text) ends the look.
private fun claimsSearchBar(layout: CleanNode): Boolean {
    for (view in layout.descendants()) {
        if (view.kind == CleanNode.Kind.EDIT_TEXT) {
            val hint = view.hint ?: return false
            if (hint.length > 3) return true
        } else if (view.kind == CleanNode.Kind.TEXT) {
            val text = view.text ?: return false
            if (text == "搜索" || text == "转发到日常") return true
        }
    }
    return false
}

private fun claimsMessageEntry(layout: CleanNode): Boolean {
    for (view in layout.descendants()) {
        if (view.kind == CleanNode.Kind.TEXT || view.kind == CleanNode.Kind.EDIT_TEXT) {
            val text = view.text ?: return false
            if (text.contains("私信")) return true
        }
    }
    return false
}

private val CONTAINER_NAMES = setOf(
    "FeedRightScaleView", "MainTitleBarContainer", "MeasureOnceFrameLayout", "AdHalfWebPageContainer", "ConsumeCommentStickerOldView",
    "MeasureOnceRelativeLayout2", "MeasureOnceRelativeLayout", "InterceptTouchRelativeLayout", "EnterBottomLayout",
)

// The views each opacity setting applies to.
internal class OpacityPlan(val bottom: List<Any>, val top: List<Any>, val right: List<Any>)

// The three regions. A View belongs to one: the bottom setting wins over the top one where both name it (the
// bottom tab bar), and the "watch full screen" entry takes the bottom setting through its parent.
internal fun planOpacity(root: CleanNode): OpacityPlan {
    val bottom = LinkedHashSet<CleanNode>()
    val top = LinkedHashSet<CleanNode>()
    val right = LinkedHashSet<CleanNode>()
    for (view in root.descendants()) {
        when (view.simpleName) {
            "MainBottomTabContainer" -> { bottom += view; top += view }
            "AdHalfWebPageContainer", "AwemeIntroInfoLayout", "MeasureOnceFrameLayout" -> bottom += view
            "MainTitleBar", "MainTabStripNew" -> top += view
            "FeedRightScaleView", "MusicCoverContainerLayout", "MeasureOnceRelativeLayout2" -> right += view
            "FpsControlFrameLayout" -> view.descendants().filter { it.kind == CleanNode.Kind.TEXT }.forEach { label ->
                val text = label.text.orEmpty()
                if (text.contains("同款") || text == "听抖音") { right += label; right += view }
            }
        }
        if (view.kind == CleanNode.Kind.SEEK_BAR) bottom += view
        if (view.kind == CleanNode.Kind.TEXT && view.text == "全屏观看") view.parent?.let { bottom += it }
    }
    top.removeAll(bottom)
    return OpacityPlan(bottom.map { it.handle }, top.map { it.handle }, right.map { it.handle })
}

// The controls the "hide video controls" setting can take off the screen, by the setting's names.
internal object VideoControls {
    const val FEEDBACK = "feedback"
    const val SEARCH = "search"
    const val MENU = "menu"
    const val DANMAKU = "danmaku"
    const val FULL_SCREEN = "full-screen"
    val ALL = listOf(FEEDBACK, SEARCH, MENU, DANMAKU, FULL_SCREEN)
}

// The Views to hide for the chosen controls. The title bar's icons are told apart by their order: the first is the
// search button, the rest are the menu. A text label stands for its whole entry, which is its parent.
internal fun planVideoControls(root: CleanNode, chosen: Set<String>): List<Any> {
    val hidden = LinkedHashSet<CleanNode>()
    for (view in root.descendants()) {
        when {
            VideoControls.FEEDBACK in chosen && view.kind == CleanNode.Kind.TEXT && view.text == "反馈" -> view.parent?.let { hidden += it }
            VideoControls.FULL_SCREEN in chosen && view.kind == CleanNode.Kind.TEXT && view.text == "全屏观看" -> view.parent?.let { hidden += it }
            VideoControls.DANMAKU in chosen && view.simpleName == "DanmakuVisibilityFrameLayout" -> hidden += view
            view.simpleName == "MainTitleBar" && (VideoControls.SEARCH in chosen || VideoControls.MENU in chosen) -> {
                val icons = view.descendants().filter { it.simpleName == "AntiBurnSmartImageView" }
                icons.forEachIndexed { index, icon ->
                    if (if (index == 0) VideoControls.SEARCH in chosen else VideoControls.MENU in chosen) hidden += icon
                }
            }
        }
    }
    return hidden.map { it.handle }
}

// The comment and share buttons on the right edge of the feed. They are told apart from their neighbours by the ID
// name of the comment button's frame and by the share button's class.
internal fun planRightButtons(root: CleanNode, comment: Boolean, share: Boolean): List<Any> =
    root.descendants().filter { view ->
        (comment && view.idName == "comment_layout" && view.simpleName == "HPFrameLayout") ||
            (share && view.simpleName == "FrameLayoutHoldTouchListener")
    }.map { it.handle }
