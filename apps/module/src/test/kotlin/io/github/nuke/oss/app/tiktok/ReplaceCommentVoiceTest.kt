package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.PickResult
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.tiktok.feature.comment.ReplaceCommentVoice
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for replacing a voice comment's recording: holding the publish, the choice, the conversion handed in and
// the audio model written before the publish runs again. The picker and the converter are stand-ins.
class ReplaceCommentVoiceTest : HookHarness() {
    class AudioModel(@JvmField val localFile: File, @JvmField val waveList: List<Int>, @JvmField val duration: Long?) {
        override fun toString() = "AudioModel(localFile=$localFile, waveList=$waveList, duration=$duration)"
    }
    class Content(@JvmField val text: String, @JvmField val audio: AudioModel?)
    class Request(@JvmField val inputContent: Content)
    class Presenter {
        val published = ArrayList<Request?>()
        fun publish(request: Request?) { published += request }
    }
    class BadModel(@JvmField val first: File, @JvmField val second: File) { override fun toString() = "AudioModel(localFile=, waveList=" }

    private val directory = Files.createTempDirectory("voice").toFile()
    private val prompts = RecordingPrompts()
    private val picker = RecordingFilePicker()
    private val publish = method(Presenter::class.java, "publish", Request::class.java)
    private val printer = method(AudioModel::class.java, "toString")
    private val now = TaskScheduler { _, task -> task(); AutoCloseable {} }

    private fun feature(converter: FakeAudioConverter = FakeAudioConverter(), model: java.lang.reflect.Method = printer) = ReplaceCommentVoice(
        resolver(CommentQueries.COMMENT_SEND to listOf(publish), CommentQueries.AUDIO_MODEL to listOf(model)), loader, prompts, { picker }, { converter },
        File(directory, "voice"), now, Logger {},
    )

    private fun recorded() = AudioModel(File(directory, "recorded.m4a").apply { writeText("recorded") }, listOf(1, 2, 3), 1_000L)

    @Test fun `a text comment is published untouched`() {
        enable(feature())
        val presenter = Presenter()
        val request = Request(Content("hi", null))
        backend.call(publish, presenter, request)
        assertEquals(listOf(request), presenter.published)
        assertTrue(prompts.choices.isEmpty())
    }

    @Test fun `a voice comment is held and sent as recorded when asked`() {
        enable(feature())
        val presenter = Presenter()
        val request = Request(Content("", recorded()))
        backend.call(publish, presenter, request)
        assertTrue(presenter.published.isEmpty())
        val choice = prompts.choices.single()
        assertEquals(listOf<Any>(R.string.host_voice_replace_choose, R.string.host_voice_replace_keep), choice.items)
        choice.onChosen(1)
        assertEquals(listOf(request), presenter.published)
        assertTrue(picker.picks.isEmpty())
    }

    @Test fun `a chosen file is converted into the audio model before the publish runs again`() {
        val converter = FakeAudioConverter(duration = 7_500)
        enable(feature(converter))
        val presenter = Presenter()
        val model = recorded()
        val request = Request(Content("", model))
        backend.call(publish, presenter, request)
        val chosen = File(directory, "song.mp3").apply { writeText("chosen") }
        picker.answers += PickResult.Picked(chosen, "song.mp3")
        prompts.choices.single().onChosen(0)
        assertEquals(listOf(listOf("audio/*")), picker.picks)
        assertSame(chosen, converter.converted.single())
        assertEquals(listOf(request), presenter.published)
        assertEquals("chosen", model.localFile.readText())
        assertTrue(model.localFile.name.endsWith(".m4a") && model.localFile.parentFile == File(directory, "voice"))
        assertEquals(7_500L, model.duration)
        assertEquals(listOf(1, 2, 3), model.waveList)
    }

    @Test fun `a failed conversion sends nothing and says why`() {
        enable(feature(FakeAudioConverter(failure = "unsupported audio format")))
        val presenter = Presenter()
        backend.call(publish, presenter, Request(Content("", recorded())))
        picker.answers += PickResult.Picked(File(directory, "x.bin").apply { writeText("x") }, "x.bin")
        prompts.choices.single().onChosen(0)
        assertTrue(presenter.published.isEmpty())
        assertEquals(R.string.host_voice_replace_failed to listOf<Any>("unsupported audio format"), prompts.toasts.single())
    }

    @Test fun `backing out of the picker sends nothing`() {
        enable(feature())
        val presenter = Presenter()
        backend.call(publish, presenter, Request(Content("", recorded())))
        prompts.choices.single().onChosen(0)
        assertTrue(presenter.published.isEmpty())
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `an audio model without one file and one length fails the install`() {
        failsToInstall(feature(model = method(BadModel::class.java, "toString")), "no longer holds one File and one Long")
    }
}
