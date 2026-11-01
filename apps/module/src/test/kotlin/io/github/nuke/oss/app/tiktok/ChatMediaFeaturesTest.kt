package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.query.ChatMediaQueries
import com.bytedance.dux.widget.DuxCheckBox
import com.ss.android.ugc.aweme.ecommerce.im.choosemedia.ECommerceIMMediaChooseActivity
import com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.viewmodel.MediaChooseViewModel2
import io.github.nuke.oss.app.tiktok.feature.chat.AutoOriginalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.github.nuke.oss.app.shared.reflect.callByName
import org.junit.Test

// Contract tests for the chat media features, with stand-ins for the album, the view model and the e-commerce picker.
class ChatMediaFeaturesTest : HookHarness() {
    class Album {
        val model = MediaChooseViewModel2()
        fun initialize() { model.setRaw(false) }
        fun viewModel(): MediaChooseViewModel2 = model
    }
    class Binder { fun bind(root: Any?): Any? = root }

    private val views = RecordingPickerViews()
    private val setter = method(MediaChooseViewModel2::class.java, "setRaw", Boolean::class.javaPrimitiveType!!)
    private val initialize = method(Album::class.java, "initialize")
    private val bind = method(Binder::class.java, "bind", Any::class.java)

    private fun feature() = AutoOriginalMedia(
        resolver(
            ChatMediaQueries.ORIGINAL_SETTER to listOf(setter), ChatMediaQueries.ALBUM_INITIALIZATION to listOf(initialize),
            ChatMediaQueries.ALBUM_VIEW_MODEL to listOf(method(Album::class.java, "viewModel")), ChatMediaQueries.ECOMMERCE_ORIGINAL_BIND to listOf(bind),
        ),
        loader, { views },
    )

    @Test fun `a new view model and the album after its reset both default to original`() {
        enable(feature())
        val constructor = MediaChooseViewModel2::class.java.getDeclaredConstructor()
        backend.call(constructor, null)
        val album = Album()
        backend.call(initialize, album)
        assertTrue(album.model.raw)
    }

    @Test fun `the e-commerce picker ticks its check box and other screens are left alone`() {
        enable(feature())
        val box = DuxCheckBox()
        views.checkbox = box
        views.activity = Any()
        backend.call(bind, Binder(), "row")
        assertFalse(box.state)
        views.activity = ECommerceIMMediaChooseActivity()
        assertEquals("row", backend.call(bind, Binder(), "row"))
        assertTrue(box.state)
    }

    open class Base {
        fun greet(name: String) = "hi $name"
        fun fail() { throw IllegalStateException("boom") }
    }
    class Child : Base()
    interface Speaks { fun speak(): String = "speaking" }
    class Talker : Speaks

    @Test fun `a method is found on the object itself, its superclass and its interfaces`() {
        assertEquals("hi you", Child().callByName("greet", "you"))
        assertEquals("speaking", Talker().callByName("speak"))
        assertTrue(runCatching { Child().callByName("greet") }.exceptionOrNull() is NoSuchMethodException)
    }

    @Test fun `an exception the called method throws comes out unwrapped`() {
        val failure = runCatching { Child().callByName("fail") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("boom", failure?.message)
    }
}
