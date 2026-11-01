package com.bytedance.ies.bullet.kit.web

// Test stand-in for the host web view that OpenExternalLinks loads by name.
class SSWebView {
    val loaded = ArrayList<String>()
    fun loadUrl(url: String) { loaded += url }
}
