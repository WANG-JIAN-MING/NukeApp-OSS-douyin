package com.ss.android.ugc.aweme.o.c2feed.core

// Test stand-in for the host feed adapter that HideRelatedRecommendations loads by name.
class C2Adapter(private val items: Int) {
    fun getItemCount(): Int = items
}
