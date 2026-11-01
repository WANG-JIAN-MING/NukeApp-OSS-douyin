package io.github.nuke.oss.app.tiktok.injector

import android.content.Context
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger

internal class AndroidModuleStrings(private val build: BuildIdentity, private val logger: Logger) : ModuleStrings {
    override fun get(context: Any, id: Int): String? {
        val host = context as? Context ?: return null
        return when (val opened = ModulePackage.open(host, build)) {
            is ModulePackage.Opened.Ready -> opened.context.getString(id)
            is ModulePackage.Opened.Unavailable -> null.also { logger.log("component=module-strings text_skipped ${opened.reason}") }
        }
    }
}
