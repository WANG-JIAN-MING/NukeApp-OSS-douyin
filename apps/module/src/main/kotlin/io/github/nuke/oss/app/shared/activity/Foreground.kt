package io.github.nuke.oss.app.shared.activity

import io.github.nuke.oss.runtime.FeatureScope

// The class name of the host Activity in front, for features whose rules differ per screen.
internal interface Foreground {
    fun install(scope: FeatureScope, featureId: String)
    fun activityName(): String?
}

internal class AndroidForeground : Foreground {
    private val activities = ResumedActivity()

    override fun install(scope: FeatureScope, featureId: String) = activities.install(scope, featureId)

    override fun activityName(): String? = activities.get()?.javaClass?.name
}
