package io.github.nuke.oss.app.tiktok.feature.feed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Puts a link on the clipboard with the Context the host passed. False means nothing was copied.
internal fun interface LinkClipboard {
    fun copy(context: Any, link: String): Boolean
}

private val androidClipboard = LinkClipboard { context, link ->
    val clipboard = (context as Context).getSystemService(ClipboardManager::class.java) ?: return@LinkClipboard false
    clipboard.setPrimaryClip(ClipData.newPlainText(null, link))
    true
}

// Copies only the short link when a video is shared as a link, instead of the host's text around it. The host's own
// copy step is skipped only once the link is on the clipboard.
@HookEntry
internal class CleanShareLink(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val clipboard: LinkClipboard = androidClipboard,
) : RuntimeFeature, UsesDexQueries {
    override val id = "CleanShareLink"
    override val dexQueries = listOf(FeedQueries.SHARE_LINK_COPY)

    override fun install(scope: FeatureScope) {
        val copy = resolver.methods(hostClassLoader, FeedQueries.SHARE_LINK_COPY).single()
        check(copy.parameterCount == 4 && copy.parameterTypes[2] == String::class.java) {
            "${copy.declaringClass.name}.${copy.name} does not take the link third"
        }
        copy.isAccessible = true
        scope.hooks.install(copy, HookSpec(
            id = "CopyBareLink",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val context = call.argument(1)
                val link = (call.argument(2) as? String)?.trim()
                if (context != null && !link.isNullOrEmpty() && clipboard.copy(context, link)) call.returnEarly(null)
            },
        ))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CleanShareLink(resolver, hostClassLoader))
        }
    }
}
