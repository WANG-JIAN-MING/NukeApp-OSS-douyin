package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.VoiceQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.HostContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The host's own JSON helper turns a content object into text and back, so a snapshot keeps exactly what the host keeps.
internal interface ContentCodec {
    fun encode(content: Any): String
    fun decode(json: String, type: Class<*>): Any
}

internal class HostContentCodec(private val hostClassLoader: ClassLoader) : ContentCodec {
    private val util by lazy { "com.ss.android.ugc.aweme.im.sdk.utils.GsonUtil".toClass(hostClassLoader) }

    // Kotlin objects keep the instance in INSTANCE; a plain static helper has none.
    private fun owner(): Any? = util.resolve().optional(silent = true).firstFieldOrNull { name = "INSTANCE" }?.getQuietly()

    private fun method(name: String, vararg parameters: Class<*>) =
        util.resolve().method { this.name = name; parameters(*parameters) }.single().of(owner())

    override fun encode(content: Any) = method("toJson", Any::class.java).invoke(content) as? String ?: error("Voice content encode failed")
    override fun decode(json: String, type: Class<*>) =
        method("fromJson", String::class.java, Class::class.java).invoke(json, type) ?: error("Voice content decode failed")
}

// A voice message as media only: serialized, and rebuilt into a fresh content object that carries nothing of the
// original conversation (no references, mentions, AI context or sender), so it can be sent anywhere.
internal class VoiceSnapshot private constructor(private val type: Class<*>, private val json: String, private val codec: ContentCodec) {
    fun freshContent(): Any {
        val decoded = codec.decode(json, type)
        val clean = type.resolve().firstConstructor { emptyParameters() }.create()
        val names = COMMON + when (type.name) {
            ENCRYPT -> listOf("skey", "tkey", "urlExpiredTs", "array")
            ENCRYPT_NEW -> listOf("skey", "tkey", "urlExpiredTs")
            else -> emptyList()
        }
        for (name in names) {
            val field = decoded.asResolver().optional(silent = true).firstFieldOrNull { this.name = name; superclass() } ?: continue
            field.copy().of(clean).set(field.get())
        }
        return clean
    }

    companion object {
        private const val PLAIN = "com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent"
        private const val ENCRYPT = "com.ss.android.ugc.aweme.im.business.mediaselectpage.sender.audio.model.EncryptAudioContent"
        private const val ENCRYPT_NEW = "com.ss.android.ugc.aweme.im.business.mediaselectpage.sender.audio.model.EncryptAudioNewContent"
        private val COMMON = listOf("type", "duration", "md5", "url", "waveList", "audioContentType", "aiAudioText")

        fun supports(content: Any): Boolean = content.javaClass.name in setOf(PLAIN, ENCRYPT, ENCRYPT_NEW)

        fun capture(content: Any, codec: ContentCodec): VoiceSnapshot {
            require(supports(content)) { "Unsupported voice content" }
            val fields = content.asResolver().optional(silent = true)
            require(((fields.firstFieldOrNull { name = "duration"; superclass() }?.get() as? Long) ?: 0) > 0) { "Voice duration is missing" }
            require(fields.firstFieldOrNull { name = "url"; superclass() }?.get() != null) { "Voice resource is missing" }
            return VoiceSnapshot(content.javaClass, codec.encode(content), codec)
        }
    }
}

// Forward a voice message from its long-press menu. The host disables forwarding for voice; only the inner media
// capability is switched on, and the host's other limits on the message (its state, a recall) still apply. Choosing
// "forward" shows a list of friends and group chats (up to 20) and sends a fresh copy of the voice to each in turn.
// A send that fails, or whose outcome is unknown, ends the whole run, because an uncertain write must not be followed
// by another. Nothing is retried.
@HookEntry
internal class VoiceForward(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val contacts: HostContacts,
    private val account: () -> Long,
    private val sender: ContentSender,
    private val codec: ContentCodec,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
    private val guardTimer: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(
        VoiceQueries.MENU_FACTORY, VoiceQueries.FORWARD_CAPABILITY, VoiceQueries.FORWARD_ACTION,
        VoiceQueries.THREAD_FORWARD_ACTION,
    ) + HostContentSender.QUERIES
    private val active = AtomicBoolean()
    // 0 idle, 1 a list is open, 2 sending. One run at a time.
    private val state = AtomicInteger()

    // Only a message the host has sent or received whole, and has not been recalled, can be forwarded.
    private fun eligible(message: Any): Boolean =
        (message.callByName("getMsgStatus") as? Number)?.toInt() in listOf(2, 5) && message.callByName("isRecalled") != true

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val methods = resolver.methods(
            hostClassLoader, VoiceQueries.MENU_FACTORY, VoiceQueries.FORWARD_CAPABILITY, VoiceQueries.FORWARD_ACTION, VoiceQueries.THREAD_FORWARD_ACTION,
        )
        methods.forEach { it.isAccessible = true }
        val factory = methods[0]
        val capability = methods[1]
        val messageType = MESSAGE.toClass(hostClassLoader)
        val contentType = CONTENT.toClass(hostClassLoader)
        active.set(true)
        scope.own(AutoCloseable { active.set(false) })
        scope.hooks.install(factory, HookSpec(id = "Menu", requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE), before = { call ->
            val message = call.argument(2) ?: return@HookSpec
            val content = call.argument(3) ?: return@HookSpec
            val delegate = call.argument(4) ?: return@HookSpec
            if (!VoiceSnapshot.supports(content) || !eligible(message)) return@HookSpec
            call.setArgument(4, Proxy.newProxyInstance(hostClassLoader, arrayOf(capability.declaringClass)) { proxy, method, values ->
                when {
                    method.name == "equals" -> proxy === values?.firstOrNull()
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "toString" -> "NukeVoiceMenuCapability"
                    method.name == capability.name && method.parameterCount == 0 && active.get() -> eligible(message)
                    else -> try { method.invoke(delegate, *(values ?: emptyArray())) } catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                }
            })
        }))
        for ((slot, action) in listOf(methods[2], methods[3]).withIndex()) {
            scope.hooks.install(action, HookSpec(id = "Forward$slot", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
                val handler = call.receiver ?: return@HookSpec
                val content = fieldOf(handler, contentType) ?: return@HookSpec
                if (!VoiceSnapshot.supports(content)) return@HookSpec
                // The host's own forward needs a share package, which a voice has none of; sending goes through the content SDK.
                call.returnEarly(null)
                val message = fieldOf(handler, messageType)
                try {
                    check(active.get() && message != null && eligible(message)) { "Voice message cannot be forwarded" }
                    begin(VoiceSnapshot.capture(content, codec))
                } catch (failure: IllegalStateException) {
                    fail(failure)
                } catch (failure: IllegalArgumentException) {
                    fail(failure)
                }
            }))
        }
    }

    // The field of the handler that holds the content or the message, found through its class and superclasses.
    private fun fieldOf(handler: Any, type: Class<*>): Any? =
        handler.asResolver().optional(silent = true).firstFieldOrNull { this.type = type; superclass() }?.get()

    private class Recipient(val name: String, val uid: String?, val conversationId: String?)

    private fun begin(snapshot: VoiceSnapshot) {
        if (!state.compareAndSet(0, 1)) return prompts.toast(R.string.host_notice_voice_busy)
        runner.run(0) {
            try {
                val owner = account()
                val list = contacts.load()
                val recipients = list.friends.map { Recipient(it.name, it.uid, null) } + list.groups.map { Recipient("👥 ${it.name}", null, it.conversationId) }
                if (recipients.isEmpty()) {
                    state.set(0)
                    return@run prompts.toast(R.string.host_notice_voice_none)
                }
                prompts.chooseMany(
                    R.string.host_voice_forward_title, recipients.map { it.name }, LIMIT, R.string.host_voice_forward_confirm, android.R.string.cancel,
                    R.string.host_voice_forward_limit, onUnavailable = { state.set(0) },
                ) { chosen ->
                    if (state.compareAndSet(1, 2)) runner.run(0) { send(owner, snapshot, chosen.sorted().map(recipients::get)) }
                }
                cancelGuard()
            } catch (failure: Exception) {
                state.set(0)
                if (failure is IllegalStateException || failure is java.io.IOException || failure is io.github.nuke.oss.app.shared.settings.HostContactsUnavailable) fail(failure)
                else throw failure
            }
        }
    }

    // The list has no cancel callback, so a list nobody confirmed frees the guard after a while; a run that is already
    // sending keeps it until it ends.
    private fun cancelGuard() {
        guardTimer.run(GUARD_MILLIS) { state.compareAndSet(1, 0) }
    }

    private fun send(owner: Long, snapshot: VoiceSnapshot, recipients: List<Recipient>) {
        prompts.toast(R.string.host_notice_voice_working)
        var done = 0
        try {
            for (recipient in recipients) {
                check(active.get()) { "Voice forwarding was stopped" }
                check(account() == owner) { "Account changed" }
                val content = snapshot.freshContent()
                val result = if (recipient.uid != null) sender.send(recipient.uid, content, TIMEOUT_MILLIS)
                    else sender.sendToConversation(checkNotNull(recipient.conversationId), content, TIMEOUT_MILLIS)
                when (result) {
                    is SendResult.Sent -> done++
                    is SendResult.Failed -> return stopped(done, recipients.size, result.detail)
                    is SendResult.Unknown -> return stopped(done, recipients.size, result.detail)
                }
            }
            logger.log("feature=$ID forwarded count=$done")
            prompts.toast(R.string.host_notice_voice_done, done)
        } catch (failure: IllegalStateException) {
            stopped(done, recipients.size, failure.message ?: failure.javaClass.simpleName)
        } finally {
            state.set(0)
        }
    }

    private fun stopped(done: Int, total: Int, reason: String) {
        logger.log("feature=$ID stopped sent=$done of=$total")
        prompts.toast(R.string.host_notice_voice_partial, done, total, reason.take(REASON_LENGTH))
    }

    private fun fail(failure: Exception) {
        logger.log("feature=$ID failed ${failure.javaClass.simpleName}")
        prompts.toast(R.string.host_notice_voice_failed, (failure.message ?: failure.javaClass.simpleName).take(REASON_LENGTH))
    }

    companion object : TikTokFeature {
        const val ID = "VoiceForward"
        private const val MESSAGE = "com.bytedance.im.core.model.Message"
        private const val CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.model.BaseContent"
        private const val LIMIT = 20
        private const val TIMEOUT_MILLIS = 30_000L
        private const val GUARD_MILLIS = 120_000L
        private const val REASON_LENGTH = 200

        override fun create(services: TikTokFeatureServices) = with(services) {
            val imAccount = ImAccount(hostClassLoader)
            val contacts = HostContacts { DouyinContacts.open(resolver, hostClassLoader, logger, ID).load() }
            listOf(VoiceForward(resolver, hostClassLoader, contacts, imAccount::id, HostContentSender(resolver, hostClassLoader), HostContentCodec(hostClassLoader), prompts(ID), logger))
        }
    }
}
