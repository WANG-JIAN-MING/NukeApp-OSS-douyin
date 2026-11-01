package io.github.nuke.oss.app.tiktok.injector

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.tiktok.feature.chat.ChatSwipe
import io.github.nuke.oss.app.tiktok.feature.chat.SwipeDetector
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

// Watches the list through its item-touch listener, which sees every touch without taking any, so taps, long presses
// and scrolling in the host are untouched. The listener type belongs to the host's RecyclerView and is implemented
// with a proxy. All View work runs on the main thread.
internal class AndroidChatSwipe : ChatSwipe {
    private val attached = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    override fun attach(list: Any, onSwipe: (row: Any) -> Unit) {
        val group = list as? ViewGroup ?: return
        if (!attached.add(group)) return
        val listener = LISTENER.toClass(group.javaClass.classLoader)
        val detector = SwipeDetector()
        var row: WeakReference<View>? = null
        val proxy = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { self, method, arguments ->
            when (method.name) {
                "onInterceptTouchEvent" -> {
                    val event = arguments[1] as MotionEvent
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            row = rowAt(group, event.x, event.y)?.let(::WeakReference)
                            detector.down(event.x, event.y)
                        }
                        MotionEvent.ACTION_UP -> if (detector.up(event.x, event.y, group.width)) row?.get()?.let(onSwipe)
                        MotionEvent.ACTION_CANCEL -> detector.cancel()
                    }
                    false
                }
                "equals" -> self === arguments[0]
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "NukeChatSwipe"
                else -> null
            }
        }
        group.asResolver().firstMethod { name = "addOnItemTouchListener"; parameters(listener); superclass() }.invoke(proxy)
    }

    private fun rowAt(group: ViewGroup, x: Float, y: Float): View? = (0 until group.childCount)
        .map(group::getChildAt)
        .firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    private companion object {
        const val LISTENER = "androidx.recyclerview.widget.RecyclerView\$OnItemTouchListener"
    }
}
