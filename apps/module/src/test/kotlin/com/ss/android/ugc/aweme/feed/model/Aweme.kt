package com.ss.android.ugc.aweme.feed.model

// Stand-in for Douyin's video model, with only the fields the feed events and their features read.
class Aweme(@JvmField var aweme_id: String?, @JvmField var duration: Int = 0, @JvmField var create_time: Long = 0)
