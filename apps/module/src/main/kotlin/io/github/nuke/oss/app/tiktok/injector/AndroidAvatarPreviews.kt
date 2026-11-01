package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.nuke.oss.app.tiktok.feature.feed.AvatarPreviews
import java.lang.ref.WeakReference
import java.util.WeakHashMap

// Watches the touches of the host's avatar preview and tells the feature when the image itself is long-pressed. It
// never changes the event or the host's result, so pinch zoom, drag to dismiss and the single tap keep working. The
// Activities are weak keys, and a preview holds only weak references, so nothing keeps an Activity alive.
internal class AndroidAvatarPreviews : AvatarPreviews {
    private val previews = WeakHashMap<Activity, Preview>()

    override fun bind(activity: Any, image: Any, onLongPress: () -> Unit) {
        val owner = activity as? Activity ?: return
        val picture = image as? View ?: return
        val next = Preview(owner, picture, onLongPress)
        val previous = synchronized(previews) { previews.put(owner, next) }
        previous?.cancel()
    }

    override fun unbind(activity: Any) {
        val owner = activity as? Activity ?: return
        synchronized(previews) { previews.remove(owner) }?.cancel()
    }

    override fun touch(view: Any, event: Any) {
        val owner = (view as? View)?.context?.let(::activityOf) ?: return
        (event as? MotionEvent)?.let { synchronized(previews) { previews[owner] }?.touch(it) }
    }

    override fun clear() {
        val all = synchronized(previews) { previews.values.toList().also { previews.clear() } }
        all.forEach { it.cancel() }
    }

    private fun activityOf(context: android.content.Context): Activity? {
        var current: android.content.Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private class Preview(activity: Activity, image: View, private val onLongPress: () -> Unit) {
        private val activityRef = WeakReference(activity)
        private val imageRef = WeakReference(image)
        private var armed = false
        private val detector = GestureDetector(activity.applicationContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onLongPress(event: MotionEvent) {
                val owner = activityRef.get() ?: return
                if (!armed || imageRef.get()?.isAttachedToWindow != true || owner.isFinishing || owner.isDestroyed) return
                onLongPress()
            }
        })

        fun touch(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val bounds = Rect()
                armed = imageRef.get()?.getGlobalVisibleRect(bounds) == true && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            }
            if (event.pointerCount > 1) return cancel()
            if (armed) detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) armed = false
        }

        fun cancel() {
            armed = false
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { detector.onTouchEvent(cancel) } finally { cancel.recycle() }
        }
    }
}
