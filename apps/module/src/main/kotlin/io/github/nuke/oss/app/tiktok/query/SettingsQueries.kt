package io.github.nuke.oss.app.tiktok.query

import io.github.nuke.oss.app.shared.dex.DexQueries
import io.github.nuke.oss.app.shared.dex.method

// Dex queries the settings entry needs, located the same way as FeedQueries.
@DexQueries
internal object SettingsQueries {
    // Douyin's Mira plugin framework, MiraResourcesManager.updateActivityResources(Activity, boolean, AssetManager):
    // when an Activity is created it swaps the host's AssetManager into the Resources that Activity exposes.
    val MIRA_ACTIVITY_RESOURCES = method {
        matcher {
            usingStrings("MiraResourcesManager updateActivityResources, newAssetManager null, ")
            paramTypes("android.app.Activity", "boolean", "android.content.res.AssetManager")
        }
    }
}
