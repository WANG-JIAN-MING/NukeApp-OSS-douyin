package com.ss.android.ugc.aweme.sharer.ui

// Stand-in for Douyin's share package builder, with only the item type the dislike action sets.
class SharePackage {
    class Builder {
        var type: String? = null
        fun itemType(type: String): Builder { this.type = type; return this }
    }
}
