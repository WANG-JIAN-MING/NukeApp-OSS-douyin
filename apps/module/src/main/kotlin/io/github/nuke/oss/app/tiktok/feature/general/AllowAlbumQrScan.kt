package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.GateQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The scanner has a boolean check that rejects QR codes read from an album image. It is answered "not rejected".
// The scanner's constructor flag is not forced on: the Hook model does not allow rewriting constructor arguments.
@HookEntry
internal class AllowAlbumQrScan(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "AllowAlbumQrScan"
    override val dexQueries = listOf(GateQueries.ALBUM_SCAN)

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, GateQueries.ALBUM_SCAN).single()
        val owner = anchor.declaringClass
        val checks = owner.declaredMethodsNamed(anchor.name).filter { it.returnType == Boolean::class.javaPrimitiveType }
        check(checks.isNotEmpty()) { "No boolean ${anchor.name} in ${owner.name}" }
        checks.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(
                id = "AllowScan$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(false) },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AllowAlbumQrScan(resolver, hostClassLoader))
        }
    }
}
