@file:JvmName("MultiInstanceHelperKt")

package com.bytedance.ies.im.core.sdk

import com.bytedance.im.core.model.ConversationListModel

// Test stand-in for the host's per-account model lookup that MessageTabMenu loads by name.
var testConversationModel: ConversationListModel? = null

fun getConversationListModel(): ConversationListModel? = testConversationModel
