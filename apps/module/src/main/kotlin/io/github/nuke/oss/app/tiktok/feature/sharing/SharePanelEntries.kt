package io.github.nuke.oss.app.tiktok.feature.sharing

import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.tiktok.query.ShareQueries
import io.github.nuke.oss.runtime.FeatureScope

// One row a feature adds at the front of the share panel's first action row. onClick gets the host Activity, the post
// the panel is about, and a function that closes the panel. title is a module string ID.
internal class SharePanelEntry(
    val key: String,
    val title: Int,
    val icon: Int,
    val onClick: (activity: Any, aweme: Any, dismissPanel: () -> Unit) -> Unit,
)

// Adds rows to the host's share panel for as long as the feature's scope lives, then takes them off and closes any
// panel that still shows them. The native rows keep their relative order.
internal interface SharePanelEntries {
    fun add(scope: FeatureScope, featureId: String, entry: SharePanelEntry)

    // What the host-backed entries resolve when a row is added; a feature that adds one declares them (see UsesDexQueries).
    companion object {
        val QUERIES: List<DexQuery> = listOf(
            ShareQueries.BIND_ROWS, ShareQueries.INITIALIZE_FUNCTION, ShareQueries.LABEL_OVERRIDE,
            ShareQueries.CLICK_OVERRIDE, ShareQueries.SHARE_UTIL,
        )
    }
}
