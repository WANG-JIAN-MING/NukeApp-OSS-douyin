package io.github.nuke.oss.testing

import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.runtime.RuntimeBootstrap

// Stands in for the application profile so the loader tests never link an application class.
object TestProfile : LoaderProfile {
    const val FIXTURE = "io.github.nuke.oss.fixture"
    const val TIKTOK = "com.ss.android.ugc.aweme"

    override val applicationId = "io.github.nuke.oss"
    override val buildId = "test"
    override val hostPackages: Set<String> = linkedSetOf(FIXTURE, TIKTOK)
    override fun createBootstrap(): RuntimeBootstrap = error("Tests inject their own bootstrap")
}
