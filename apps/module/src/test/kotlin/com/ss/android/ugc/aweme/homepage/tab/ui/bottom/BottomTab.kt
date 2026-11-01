package com.ss.android.ugc.aweme.homepage.tab.ui.bottom

import android.content.Context

// Test stand-in for the host's bottom bar tab that MessageTabMenu loads by name. TabContent plays the View whose long
// press the feature takes over; both long-press overloads answer false, as the host's would with no listener.
class TabContent {
    fun performLongClick(): Boolean = false
    fun performLongClick(x: Float, y: Float): Boolean = false
}

class BottomTab(@Suppress("UNUSED_PARAMETER") context: Context?, private val id: String, @Suppress("UNUSED_PARAMETER") selected: Boolean) {
    private val content = TabContent()
    fun getCurrentTabId(): String = id
    fun getView(): TabContent = content
}
