package com.bytedance.im.core.model

// Test stand-in for the host message model that PreventMessageRecall loads by name.
class Message(private val recalled: Boolean, private val self: Boolean = false, private val msgId: Long = 0) {
    fun isRecalled(): Boolean = recalled
    fun isSelf(): Boolean = self
    fun getMsgId(): Long = msgId
    fun getMsgStatus(): Int = 2
}
