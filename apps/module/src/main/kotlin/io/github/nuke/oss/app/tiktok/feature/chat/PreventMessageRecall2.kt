package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.RecallQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

// A decoration on one message row.
internal interface RecallNotice {
    // Flare rows measure their own children and ignore padding, so their measured height is extended afterwards.
    fun extendMeasurement(root: Any)
    fun remove()
}

// The row work the notice needs. All of it runs on the main thread.
internal interface RecallNoticeUi {
    // Follows the Activity in front for as long as the feature is installed, which the scan of visible rows needs.
    fun install(scope: FeatureScope, featureId: String)

    // The row's root View of a RecyclerView holder; null when it is not a group View.
    fun itemRoot(holder: Any): Any?

    // Puts the "recall attempted" line under the row; null when the row is neither a Flare row nor a frame layout.
    fun show(root: Any, flare: Boolean): RecallNotice?

    // The adapter and holder of every row of the chat lists now on screen.
    fun visibleRows(recycler: Class<*>, adapter: Class<*>): List<Pair<Any, Any>>
}

// Anti-recall 2: a recall command from another person is held back before the host applies it, so the message stays
// as it was and nothing is deleted or marked recalled. Where the host takes a recall as a command (a recall message, or
// an ext update marking the message recalled), the feature answers that command as handled and keeps the message; the
// row then gets a small "recall attempted" line, remembered across restarts. Only incoming messages that still exist
// qualify; your own, deleted and already recalled ones, and commands for another conversation, are left to the host.
// The line is separate from the interception: if the row hooks cannot be installed, the interception still works.
@HookEntry
internal class PreventMessageRecall2(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val scheduler: TaskScheduler,
    private val memory: FeatureMemory,
    private val ui: () -> RecallNoticeUi,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(
        RecallQueries.RECALL, RecallQueries.UPDATE_EXT, RecallQueries.PRE_RECALL, RecallQueries.PRE_UPDATE_EXT,
        RecallQueries.MESSAGE_BY_UUID, RecallQueries.MESSAGE_BY_ID, RecallQueries.CACHED_BY_UUID,
        RecallQueries.CACHED_BY_ID, RecallQueries.STORE_COMMAND, RecallQueries.PRE_STORE_COMMAND,
        RecallQueries.COMPLETE, RecallQueries.PRE_COMPLETE,
    )
    private val active = AtomicBoolean()
    private val index = RecallNoticeIndex(RecallNoticeIndex.decode(memory.read(ID, NOTICES)))
    private val bindings = WeakHashMap<Any, Binding>()
    private val ext = NamedField("ext")
    private val messageType = NamedField("message_type")
    private val conversationId = NamedField("conversation_id")
    private val securityConversationId = NamedField("security_conversation_id")
    private val serverMessageId = NamedField("server_message_id")

    private class Binding(val key: RecallNoticeKey, adapter: Any, holder: Any) {
        val adapter = WeakReference(adapter)
        val holder = WeakReference(holder)
        var notice: RecallNotice? = null
    }

    override fun install(scope: FeatureScope) {
        val methods = resolver.methods(
            hostClassLoader, RecallQueries.RECALL, RecallQueries.UPDATE_EXT, RecallQueries.PRE_RECALL, RecallQueries.PRE_UPDATE_EXT,
            RecallQueries.MESSAGE_BY_UUID, RecallQueries.MESSAGE_BY_ID, RecallQueries.CACHED_BY_UUID, RecallQueries.CACHED_BY_ID,
            RecallQueries.STORE_COMMAND, RecallQueries.PRE_STORE_COMMAND, RecallQueries.COMPLETE, RecallQueries.PRE_COMPLETE,
        )
        methods.forEach { it.isAccessible = true }
        val (recall, updateExt, preRecall, preUpdateExt) = methods
        val (byUuid, byId, cachedByUuid, cachedById) = methods.subList(4, 8)
        val (storeCommand, preStoreCommand, complete, preComplete) = methods.subList(8, 12)
        val completion = complete.declaringClass.resolve().firstConstructor { emptyParameters() }
        val outcome = complete.parameterTypes.single().resolve().constructor { parameterCount = 1 }.single()
        active.set(true)
        scope.own(AutoCloseable { active.set(false); releaseNotices() })

        for ((slot, command) in listOf(recall, updateExt).withIndex()) {
            scope.hooks.install(command, HookSpec(id = "Command$slot", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                val processor = call.receiver ?: return@HookSpec
                val body = call.argument(2) ?: return@HookSpec
                val fields = ext.of(body)?.get(body) as? Map<*, *> ?: return@HookSpec
                val type = (messageType.of(body)?.get(body) as? Number)?.toInt()
                val target = ChatRecallPolicy.target(type, fields) ?: return@HookSpec
                val dao = processor.callByName("getIMMsgDaoDelegate") ?: return@HookSpec
                val message = target.uuid?.let { byUuid.invoke(dao, it) } ?: target.serverId?.let { byId.invoke(dao, it) } ?: return@HookSpec
                if (!shouldPreserve(processor, body, target, message)) return@HookSpec
                // This acknowledges the command as handled locally. It is not an answer to a send or a recall request.
                val done = completion.create()
                if (canStoreCommand(type, fields, body, message)) storeCommand.invoke(processor, body, fields["s:client_message_id"])
                complete.invoke(done, outcome.create(true))
                call.returnEarly(done)
                record(processor, message)
            }))
        }
        for ((slot, command) in listOf(preRecall, preUpdateExt).withIndex()) {
            scope.hooks.install(command, HookSpec(id = "PreCommand$slot", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                val processor = call.receiver ?: return@HookSpec
                val saveData = call.argument(0) ?: return@HookSpec
                val body = call.argument(1) ?: return@HookSpec
                val fields = ext.of(body)?.get(body) as? Map<*, *> ?: return@HookSpec
                val type = (messageType.of(body)?.get(body) as? Number)?.toInt()
                val target = ChatRecallPolicy.target(type, fields) ?: return@HookSpec
                val conversation = conversationId.of(body)?.get(body) as? String ?: return@HookSpec
                // The pipeline cache, not the database: the message can arrive earlier in the same batch.
                val message = target.uuid?.let { cachedByUuid.invoke(processor, conversation, it) }
                    ?: target.serverId?.let { cachedById.invoke(processor, it, conversation) } ?: return@HookSpec
                if (!shouldPreserve(processor, body, target, message)) return@HookSpec
                if (canStoreCommand(type, fields, body, message)) preStoreCommand.invoke(processor, saveData, fields["s:client_message_id"], body)
                // The host updates the command chain's integrity and index tracking here.
                preComplete.invoke(processor, true)
                call.returnEarly(saveData)
                record(processor, message)
            }))
        }
        try {
            installNotice(scope)
        } catch (failure: ReflectiveOperationException) {
            logger.log("feature=$ID notice_unavailable ${failure.javaClass.simpleName}")
        } catch (failure: IllegalStateException) {
            logger.log("feature=$ID notice_unavailable ${failure.message}")
        }
    }

    private fun installNotice(scope: FeatureScope) {
        val adapterType = CELL_ADAPTER.toClass(hostClassLoader)
        val holderType = VIEW_HOLDER.toClass(hostClassLoader)
        val recyclerType = RECYCLER.toClass(hostClassLoader)
        val flareType = FLARE.toClass(hostClassLoader)
        fun one(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
            val found = type.resolve().optional(silent = true).method {
                this.name = name
                parameters(*parameters)
                isSynthetic = false
            }
            check(found.size == 1) { "Expected one $name in ${type.name}, found ${found.size}" }
            return found.single().self.apply { isAccessible = true }
        }
        val bind = one(adapterType, "onBindViewHolder", holderType, Int::class.javaPrimitiveType!!, List::class.java)
        val recycled = one(adapterType, "onViewRecycled", holderType)
        val attached = one(adapterType, "onViewAttachedToWindow", holderType)
        val measure = one(flareType, "onMeasure", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val messageType = MESSAGE.toClass(hostClassLoader)
        val messageAtCandidates = adapterType.resolve().optional(silent = true).method {
            returnType = messageType
            parameters(Int::class)
        }
        check(messageAtCandidates.size == 1) { "Expected one message getter in ${adapterType.name}, found ${messageAtCandidates.size}" }
        val messageAt = messageAtCandidates.single().self.apply { isAccessible = true }
        val rows = ui()
        rows.install(scope, ID)
        fun position(holder: Any) = (holder.callByName("getAdapterPosition") as? Number)?.toInt() ?: -1
        fun bindRow(adapter: Any, holder: Any, position: Int) = bindNotice(rows, adapterType, messageAt, flareType, adapter, holder, position)
        scope.hooks.install(bind, HookSpec(
            id = "NoticeBind",
            before = { call -> call.argument(0)?.let { forget(rows, it) } },
            after = { call ->
                val adapter = call.receiver
                val holder = call.argument(0)
                if (call.outcome is CallOutcome.Returned && active.get() && adapter != null && holder != null) bindRow(adapter, holder, call.argument(1) as Int)
            },
        ))
        scope.hooks.install(recycled, HookSpec(id = "NoticeRecycle", before = { call -> call.argument(0)?.let { forget(rows, it) } }))
        scope.hooks.install(attached, HookSpec(id = "NoticeAttach", after = { call ->
            val adapter = call.receiver
            val holder = call.argument(0)
            if (call.outcome is CallOutcome.Returned && active.get() && adapter != null && holder != null) bindRow(adapter, holder, position(holder))
        }))
        scope.hooks.install(measure, HookSpec(id = "NoticeMeasure", after = { call ->
            val root = call.receiver
            if (call.outcome is CallOutcome.Returned && active.get() && root != null) synchronized(bindings) { bindings[root]?.notice }?.extendMeasurement(root)
        }))
        noticeRefresh = {
            for ((adapter, holder) in rows.visibleRows(recyclerType, adapterType)) bindRow(adapter, holder, position(holder))
        }
        refreshVisibleRows = { key ->
            val matching = synchronized(bindings) { bindings.values.toList() }
            for (binding in matching) {
                if (!binding.key.matches(key)) continue
                val adapter = binding.adapter.get() ?: continue
                val holder = binding.holder.get() ?: continue
                val place = position(holder)
                val message = messageAtOrNull(adapterType, messageAt, adapter, place) ?: continue
                if (keyOf(key.account, message)?.matches(key) != true) continue
                // The host's ordinary bind, layout and refresh path, with no change to the message.
                adapter.callByName("notifyItemChanged", place)
            }
        }
        scheduler.schedule(0) { if (active.get()) noticeRefresh() }
    }

    // Set when the row hooks went in; null-safe no-ops otherwise.
    private var noticeRefresh: () -> Unit = {}
    private var refreshVisibleRows: (RecallNoticeKey) -> Unit = {}

    private fun messageAtOrNull(adapterType: Class<*>, messageAt: Method, adapter: Any, position: Int): Any? {
        if (position < 0 || !adapterType.isInstance(adapter)) return null
        val count = (adapter.callByName("getItemCount") as? Number)?.toInt() ?: return null
        return if (position < count) messageAt.invoke(adapter, position) else null
    }

    private fun bindNotice(
        rows: RecallNoticeUi, adapterType: Class<*>, messageAt: Method, flareType: Class<*>, adapter: Any, holder: Any, position: Int,
    ) {
        val root = rows.itemRoot(holder) ?: return
        synchronized(bindings) { bindings.remove(root) }?.notice?.remove()
        val account = currentAccount() ?: return
        val message = messageAtOrNull(adapterType, messageAt, adapter, position) ?: return
        val key = keyOf(account, message) ?: return
        val binding = Binding(key, adapter, holder)
        synchronized(bindings) { bindings[root] = binding }
        if (!index.contains(key)) return
        binding.notice = rows.show(root, flareType.isInstance(root))
    }

    private fun forget(rows: RecallNoticeUi, holder: Any) {
        val root = rows.itemRoot(holder) ?: return
        synchronized(bindings) { bindings.remove(root) }?.notice?.remove()
    }

    private fun releaseNotices() {
        val all = synchronized(bindings) { bindings.values.toList().also { bindings.clear() } }
        scheduler.schedule(0) { all.forEach { it.notice?.remove() } }
    }

    // Remembers the message and refreshes the rows that show it. Runs inside the host's command handling, so the
    // storing and the View work are handed to the main thread; a failure there never undoes the interception.
    private fun record(processor: Any, message: Any) {
        val account = (processor.callByName("getUid") as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: return
        val key = keyOf(account, message) ?: return
        val keys = index.mark(key)
        scheduler.schedule(0) {
            if (!active.get()) return@schedule
            memory.write(ID, NOTICES, RecallNoticeIndex.encode(keys))
            if (currentAccount() == account) {
                noticeRefresh()
                refreshVisibleRows(key)
            }
        }
        logger.log("feature=$ID recall_held")
    }

    private fun keyOf(account: String, message: Any): RecallNoticeKey? {
        val conversation = message.callByName("getConversationId") as? String ?: return null
        return RecallNoticeKey(
            account, conversation, (message.callByName("getUuid") as? String).orEmpty(), (message.callByName("getMsgId") as? Number)?.toLong() ?: 0,
        ).takeIf { it.isValid() }
    }

    private fun currentAccount(): String? {
        val service = ACCOUNT_SERVICE.toClass(hostClassLoader).resolve()
            .firstMethod { name = "userService"; emptyParameters() }.invoke() ?: return null
        if (service.callByName("isLogin") != true) return null
        return (service.callByName("getCurUserId") as? String)?.toLongOrNull()?.takeIf { it > 0 }?.toString()
    }

    private fun shouldPreserve(processor: Any, body: Any, target: ChatRecallPolicy.Target, message: Any): Boolean {
        if (target.uuid != null && message.callByName("getUuid") != target.uuid) return false
        if (target.serverId != null && (message.callByName("getMsgId") as? Number)?.toLong() != target.serverId) return false
        val conversations = listOfNotNull(conversationId.of(body)?.get(body) as? String, securityConversationId.of(body)?.get(body) as? String)
            .filter(String::isNotBlank).toSet()
        return ChatRecallPolicy.shouldPreserve(
            isSelf = message.callByName("isSelf") as? Boolean, isDeleted = message.callByName("isDeleted") as? Boolean,
            isRecalled = message.callByName("isRecalled") as? Boolean, sender = (message.callByName("getSender") as? Number)?.toLong(),
            currentUid = (processor.callByName("getUid") as? Number)?.toLong(),
            localConversationId = message.callByName("getConversationId") as? String, commandConversationIds = conversations,
        )
    }

    private fun canStoreCommand(type: Int?, fields: Map<*, *>, body: Any, message: Any): Boolean = ChatRecallPolicy.canStoreCommand(
        type, fields["s:client_message_id"] as? String, message.callByName("getUuid") as? String,
        (serverMessageId.of(body)?.get(body) as? Number)?.toLong(), (message.callByName("getMsgId") as? Number)?.toLong(),
    )

    companion object : TikTokFeature {
        const val ID = "PreventMessageRecall2"
        private const val NOTICES = "notices"
        private const val MESSAGE = "com.bytedance.im.core.model.Message"
        private const val CELL_ADAPTER = "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.CellComposeAdapter"
        private const val VIEW_HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"
        private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        private const val FLARE = "com.ss.android.ugc.aweme.im.flareui.host.FlareUIHostView"
        private const val ACCOUNT_SERVICE = "com.ss.android.ugc.aweme.account.AccountProxyService"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(PreventMessageRecall2(resolver, hostClassLoader, scheduler, memory, recallNoticeUi, logger))
        }
    }
}
