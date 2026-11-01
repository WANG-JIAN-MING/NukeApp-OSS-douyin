package io.github.nuke.oss.app.tiktok.sdk.screen

import io.github.nuke.oss.runtime.FeatureScope

// The screen in front, as the clean mode needs it: a snapshot to plan from, and the means to hide and bring back.
internal interface CleanScreen {
    fun install(scope: FeatureScope, featureId: String)
    // Null while no host Activity is in front.
    fun snapshot(): CleanNode?
    // keepClickable hides by transparency, so the controls still take taps; otherwise they are made invisible.
    fun hide(plan: CleanPlan, keepClickable: Boolean)
    fun restore()
}
