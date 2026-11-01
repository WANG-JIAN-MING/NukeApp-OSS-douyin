package io.github.nuke.oss.app.tiktok.feature.general

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.net.URLDecoder

// Third-party links open through a security interstitial that wraps the real address. The web view's loadUrl gets
// the address the wrapper points at instead.
@HookEntry
internal class OpenExternalLinks(private val hostClassLoader: ClassLoader) : RuntimeFeature {
    override val id = "OpenExternalLinks"

    override fun install(scope: FeatureScope) {
        val loadUrl = WEB_VIEW.toClass(hostClassLoader).resolve().firstMethod { name = "loadUrl"; parameters(String::class) }.self
        scope.hooks.install(loadUrl, HookSpec(
            id = "UnwrapLink",
            requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
            before = { call ->
                val target = (call.argument(0) as? String)?.let(::unwrapSecurityLink)
                if (target != null) call.setArgument(0, target)
            },
        ))
    }

    companion object : TikTokFeature {
        private const val WEB_VIEW = "com.bytedance.ies.bullet.kit.web.SSWebView"
        private const val INTERSTITIAL = "https://seclink.bytedance.com?app_version="
        private val TARGET = Regex("target=([^&]+)")

        // The wrapped address without its query string, or null when this is not the interstitial or names no target.
        internal fun unwrapSecurityLink(url: String): String? {
            val decoded = try {
                URLDecoder.decode(url, Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (!decoded.startsWith(INTERSTITIAL)) return null
            return TARGET.find(decoded)?.groupValues?.get(1)?.substringBefore('?')?.takeIf { it.isNotEmpty() }
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(OpenExternalLinks(hostClassLoader))
        }
    }
}
