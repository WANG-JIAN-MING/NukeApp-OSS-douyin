package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import org.junit.Test

// A feature follows the foreground Activity once for its prompts and again for its clipboard; CommentDetails does both,
// and the second install used to fail with a duplicate Hook subscription.
class ResumedActivityTest : HookHarness() {
    private class TwoFollowers : RuntimeFeature {
        override val id = "TwoFollowers"
        val first = ResumedActivity()
        val second = ResumedActivity()
        override fun install(scope: FeatureScope) {
            first.install(scope, id)
            second.install(scope, id)
        }
    }

    @Test fun `a feature can follow the foreground Activity from two places`() {
        enable(TwoFollowers())
    }
}
