package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.FilePicker
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.PickResult
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.media.AudioConversion
import io.github.nuke.oss.app.shared.media.AudioConverter
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

// Lets a voice comment carry an audio file you choose instead of what was recorded, ("评论区发语音替换").
// When a comment with a recording is about to be published, the publish is held and a list asks: choose an audio
// file, or send the recording as it is. A chosen file is copied, converted to M4A (the host's own recording format)
// and written into the comment's audio model, the file and its length; the recorded waveform is kept. The publish then
// runs again with the same presenter and request, and only that call passes. Backing out of the list, the picker or a
// failed conversion sends nothing, and the recording stays in the input. The file comes from the system file
// picker.
@HookEntry
internal class ReplaceCommentVoice(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val prompts: HostPrompts,
    private val picker: () -> FilePicker,
    private val converter: () -> AudioConverter,
    private val voiceDirectory: File,
    private val scheduler: TaskScheduler,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.COMMENT_SEND, CommentQueries.AUDIO_MODEL)
    private val replaying = ThreadLocal<Any?>()

    // Where the audio model keeps its file and its length; both are final in the host and written after construction.
    private class AudioFields(val file: Field, val duration: Field)

    override fun install(scope: FeatureScope) {
        val (send, printer) = resolver.methods(hostClassLoader, CommentQueries.COMMENT_SEND, CommentQueries.AUDIO_MODEL)
        val model = printer.declaringClass
        val fields = audioFields(model)
        check(send.parameterCount >= 1) { "${send.declaringClass.name}.${send.name} takes no publish request" }
        check(send.returnType == Void.TYPE) { "${send.declaringClass.name}.${send.name} returns ${send.returnType.name}" }
        send.isAccessible = true
        prompts.install(scope)
        val files = picker()
        files.install(scope, ID)
        val audio = converter()
        val finder = ChatMessageFinder(model)
        val active = AtomicBoolean(true)
        scope.own(AutoCloseable { active.set(false) })
        scope.hooks.install(send, HookSpec(
            id = "VoicePublish",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val request = call.argument(0) ?: return@HookSpec
                if (replaying.get() === request) return@HookSpec
                val recording = finder.find(request) ?: return@HookSpec
                val presenter = call.receiver
                val arguments = Array(call.argumentCount) { call.argument(it) }
                call.returnEarly(null)
                val resend = { publish(send, presenter, arguments, request) }
                prompts.choose(
                    R.string.host_voice_replace_title, listOf<Any>(R.string.host_voice_replace_choose, R.string.host_voice_replace_keep),
                    android.R.string.cancel, onUnavailable = resend, onCancel = {},
                ) { index ->
                    if (index != 0) return@choose resend()
                    files.pick(listOf("audio/*"), MAX_BYTES) { picked ->
                        if (!active.get()) return@pick
                        when (picked) {
                            is PickResult.Cancelled -> Unit
                            is PickResult.Failed -> prompts.toast(R.string.host_voice_replace_failed, picked.reason)
                            is PickResult.Picked -> replace(audio, picked.file, recording, fields, resend)
                        }
                    }
                }
            },
        ))
    }

    // The single File and the single boxed Long the model declares; anything else means the host changed shape.
    private fun audioFields(model: Class<*>): AudioFields {
        val declared = model.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val file = declared.filter { it.type == File::class.java }
        val duration = declared.filter { it.type == Long::class.javaObjectType }
        check(file.size == 1 && duration.size == 1) { "${model.name} no longer holds one File and one Long" }
        return AudioFields(file.single().apply { isAccessible = true }, duration.single().apply { isAccessible = true })
    }

    // Runs on the picker's background thread.
    private fun replace(audio: AudioConverter, chosen: File, recording: Any, fields: AudioFields, resend: () -> Unit) {
        if (!voiceDirectory.isDirectory && !voiceDirectory.mkdirs()) return prompts.toast(R.string.host_voice_replace_failed, "no voice directory")
        voiceDirectory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - KEEP_MS }?.forEach { it.delete() }
        val output = File(voiceDirectory, "voice-${System.currentTimeMillis()}.m4a")
        try {
            when (val converted = audio.toM4a(chosen, output)) {
                is AudioConversion.Failed -> {
                    logger.log("feature=$ID convert_failed")
                    prompts.toast(R.string.host_voice_replace_failed, converted.reason)
                }
                is AudioConversion.Converted -> {
                    fields.file.set(recording, converted.file)
                    fields.duration.set(recording, converted.durationMillis)
                    logger.log("feature=$ID replaced duration_ms=${converted.durationMillis}")
                    scheduler.schedule(0) { resend() }
                }
            }
        } finally {
            chosen.delete()
        }
    }

    // Runs on the main thread, from the dialog or the scheduler. Only this exact call passes the hook.
    private fun publish(send: Method, presenter: Any?, arguments: Array<Any?>, request: Any) {
        replaying.set(request)
        try {
            send.invoke(presenter, *arguments)
        } catch (failure: InvocationTargetException) {
            logger.log("feature=$ID publish_failed ${failure.targetException.javaClass.simpleName}")
            prompts.toast(R.string.host_voice_replace_failed, failure.targetException.javaClass.simpleName)
        } catch (failure: IllegalAccessException) {
            logger.log("feature=$ID publish_failed ${failure.javaClass.simpleName}")
        } finally {
            replaying.remove()
        }
    }

    companion object : TikTokFeature {
        const val ID = "ReplaceCommentVoice"
        private const val MAX_BYTES = 20L * 1024 * 1024
        // Converted voices stay a day, long enough for the upload that follows the publish.
        private const val KEEP_MS = 24L * 60 * 60 * 1000

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ReplaceCommentVoice(
                resolver, hostClassLoader, prompts(ID), { filePicker("voice") }, audioConverter,
                streakDirectory.resolveSibling("voice"), scheduler, logger,
            ))
        }
    }
}
