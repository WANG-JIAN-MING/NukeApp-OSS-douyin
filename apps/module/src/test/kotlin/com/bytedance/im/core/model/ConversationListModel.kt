package com.bytedance.im.core.model

// Test stand-in for the conversation list model whose mark-all step MessageTabMenu calls.
class ConversationListModel {
    var marked = 0
    var failure: RuntimeException? = null
    fun markAllConversationRead() {
        failure?.let { throw it }
        marked++
    }
}
