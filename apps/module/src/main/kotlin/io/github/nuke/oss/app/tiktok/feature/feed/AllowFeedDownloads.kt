package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Lifts the local download restrictions: the download permission checker reports NORMAL, a gallery download no longer
// sees a banned download status, and the privacy check that asks the server whether a video may be saved answers
// "allowed". The permission checker is shared by several actions, so only the one whose kind is DOWNLOAD is changed.
@HookEntry
internal class AllowFeedDownloads(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) : RuntimeFeature, UsesDexQueries {
    override val id = "AllowFeedDownloads"
    override val dexQueries = listOf(
        DownloadQueries.PERMISSION, DownloadQueries.CHECKER_KIND, DownloadQueries.GALLERY_DOWNLOAD,
        DownloadQueries.PRIVACY_DOWNLOAD, DownloadQueries.PERMISSION_STATUS, DownloadQueries.AWEME_DOWNLOAD_STATUS,
        DownloadQueries.PRIVACY_STATUS, DownloadQueries.PRIVACY_MESSAGE,
    )
    private val status = NamedField("status")

    override fun install(scope: FeatureScope) {
        val (permission, kind, gallery, privacy) = resolver.methods(
            hostClassLoader, DownloadQueries.PERMISSION, DownloadQueries.CHECKER_KIND, DownloadQueries.GALLERY_DOWNLOAD, DownloadQueries.PRIVACY_DOWNLOAD,
        )
        val (statusField, downloadStatus, privacyStatus, privacyMessage) = resolver.fields(
            hostClassLoader, DownloadQueries.PERMISSION_STATUS, DownloadQueries.AWEME_DOWNLOAD_STATUS,
            DownloadQueries.PRIVACY_STATUS, DownloadQueries.PRIVACY_MESSAGE,
        )
        listOf(permission, kind, gallery, privacy).forEach { it.isAccessible = true }
        listOf(statusField, downloadStatus, privacyStatus, privacyMessage).forEach { it.isAccessible = true }
        val normal = statusField.type.enumConstants?.singleOrNull { (it as Enum<*>).name == "NORMAL" } ?: error("${statusField.type.name} has no NORMAL")
        val responseType = PRIVACY_RESPONSE.toClass(hostClassLoader)
        val just = privacy.returnType.resolve().firstMethod { name = "just"; parameters(Any::class); superclass() }.self
        val newResponse = responseType.resolve().firstConstructor { emptyParameters() }
        scope.hooks.install(permission, HookSpec(id = "DownloadPermission", after = { call ->
            val checker = call.receiver
            val answer = (call.outcome as? CallOutcome.Returned)?.value
            if (checker != null && answer != null && (kind.invoke(checker) as? Enum<*>)?.name == "DOWNLOAD") statusField.set(answer, normal)
        }))
        scope.hooks.install(gallery, HookSpec(id = "GalleryStatus", before = { call ->
            val aweme = call.argument(0) ?: return@HookSpec
            val state = status.of(aweme)?.get(aweme) ?: return@HookSpec
            downloadStatus.setInt(state, 0)
        }))
        scope.hooks.install(privacy, HookSpec(id = "PrivacyCheck", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val response = newResponse.create()
            privacyStatus.set(response, 0)
            privacyMessage.set(response, "")
            call.returnEarly(just.invoke(null, response))
        }))
    }

    companion object : TikTokFeature {
        private const val PRIVACY_RESPONSE = "com.ss.android.ugc.aweme.feed.share.video.SharePrivacyVideoApi\$PrivacyVideoResponse"

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(AllowFeedDownloads(resolver, hostClassLoader)) }
    }
}
