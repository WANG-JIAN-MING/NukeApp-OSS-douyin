package com.ss.android.ugc.aweme.im.sdk.relations

// Test stand-in for the teen mode manager that HideTeenModeDialog finds by name: two methods taking a flag second, one of
// them the state callback that is never taken over, one with a result and one with another shape.
class IMTeenModeManager {
    val shown = ArrayList<String>()
    fun show(scene: Any?, visible: Boolean) { shown += "show"; }
    fun onStateChanged(scene: Any?, visible: Boolean) { shown += "state" }
    fun count(scene: Any?, visible: Boolean): Int = 7
    fun other(value: Int) { shown += "other" }
}
