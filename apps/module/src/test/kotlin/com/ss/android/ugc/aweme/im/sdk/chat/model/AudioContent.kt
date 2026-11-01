package com.ss.android.ugc.aweme.im.sdk.chat.model

// Test stand-ins for the host's chat content: the base type the forward handler holds, a plain voice content with the
// media fields the snapshot copies and one that it must not (a mention), and a content of another kind.
open class BaseContent

class AudioContent : BaseContent() {
    @JvmField var type: Int = 0
    @JvmField var duration: Long = 0
    @JvmField var md5: String? = null
    @JvmField var url: Any? = null
    @JvmField var waveList: List<Int>? = null
    @JvmField var audioContentType: Int = 0
    @JvmField var aiAudioText: String? = null
    @JvmField var mention: String? = null
}

class TextContent : BaseContent()
