package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.tiktok.feature.playback.AutoPlayButton
import io.github.nuke.oss.app.tiktok.feature.playback.KeepAutoPlayOn
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import java.util.Collections
import java.util.WeakHashMap

// Floats the autoplay switch over the feed's Activity: one draggable round button added to the Activity's content, so it
// needs no overlay permission and lives and dies with that window. The host's right-hand bar only says which Activity
// the feed is in; the button shows while one of those bars is on screen and hides when the user leaves the feed. All
// View work runs on the main thread.
internal class AndroidAutoPlayButton(private val build: BuildIdentity, private val logger: Logger) : AutoPlayButton {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val floating: MutableSet<FloatingSwitch> = Collections.newSetFromMap(WeakHashMap())
    // Where the user left the switch, as fractions of the free space in the window, so the next window starts there.
    private var fromLeft = START_LEFT
    private var fromTop = START_TOP

    override fun attach(bar: Any, active: () -> Boolean, onClick: () -> Unit) {
        val view = bar as? View ?: return logger.log("feature=${KeepAutoPlayOn.ID} bar_skipped not a View")
        main.post { float(view, active, onClick) }
    }

    override fun update(active: Boolean) {
        main.post { floating.forEach { it.show(active) } }
    }

    private fun float(bar: View, active: () -> Boolean, onClick: () -> Unit) {
        val activity = activityOf(bar.context) ?: return logger.log("feature=${KeepAutoPlayOn.ID} button_skipped no Activity")
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: return logger.log("feature=${KeepAutoPlayOn.ID} button_skipped no content view")
        val existing = content.findViewWithTag<FloatingSwitch>(TAG)
        if (existing != null) {
            existing.bars += bar
            return
        }
        val module = when (val opened = ModulePackage.open(activity, build)) {
            is ModulePackage.Opened.Ready -> opened.context
            is ModulePackage.Opened.Unavailable -> return logger.log("feature=${KeepAutoPlayOn.ID} button_skipped ${opened.reason}")
        }
        val view = FloatingSwitch(
            activity, module.getDrawable(R.drawable.ic_host_autoplay),
            module.getString(R.string.host_autoplay_on_description), module.getString(R.string.host_autoplay_off_description),
            fromLeft, fromTop,
        ) { left, top ->
            fromLeft = left
            fromTop = top
        }
        view.tag = TAG
        view.bars += bar
        view.setOnClickListener { onClick() }
        view.show(active())
        floating += view
        content.addView(view)
    }

    private fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    // The round button. A touch that moves past the touch slop drags it within its parent; one that does not is a click.
    private class FloatingSwitch(
        context: Context,
        icon: Drawable?,
        private val onText: String,
        private val offText: String,
        private var fromLeft: Float,
        private var fromTop: Float,
        private val moved: (left: Float, top: Float) -> Unit,
    ) : ImageView(context) {
        val bars: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
        private val size = (SIZE_DP * context.resources.displayMetrics.density).toInt()
        private val margin = MARGIN_DP * context.resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var placed = false
        private var dragging = false
        private var touchX = 0f
        private var touchY = 0f
        private var startX = 0f
        private var startY = 0f
        private val sync = ViewTreeObserver.OnGlobalLayoutListener {
            place()
            val wanted = if (bars.any { it.isShown && it.width > 0 }) VISIBLE else GONE
            if (visibility != wanted) visibility = wanted
        }

        init {
            setImageDrawable(icon)
            setPadding(size / 4, size / 4, size / 4, size / 4)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x66, 0, 0, 0))
            }
            elevation = ELEVATION
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        }

        fun show(on: Boolean) {
            alpha = if (on) ON_ALPHA else OFF_ALPHA
            contentDescription = if (on) onText else offText
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnGlobalLayoutListener(sync)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnGlobalLayoutListener(sync)
            super.onDetachedFromWindow()
        }

        // The window's size is only known after its first layout, so the first position waits for it.
        private fun place() {
            val box = parent as? View ?: return
            if (placed || box.width <= 0) return
            placed = true
            moveTo(fromLeft * (box.width - size), fromTop * (box.height - size))
        }

        private fun moveTo(left: Float, top: Float) {
            val box = parent as? View ?: return
            x = left.coerceIn(margin, (box.width - size - margin).coerceAtLeast(margin))
            y = top.coerceIn(margin, (box.height - size - margin).coerceAtLeast(margin))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    touchX = event.rawX
                    touchY = event.rawY
                    startX = x
                    startY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) moveTo(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) remember() else performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) remember()
                    dragging = false
                }
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()

        private fun remember() {
            val box = parent as? View ?: return
            val freeWidth = (box.width - size).coerceAtLeast(1)
            val freeHeight = (box.height - size).coerceAtLeast(1)
            fromLeft = (x / freeWidth).coerceIn(0f, 1f)
            fromTop = (y / freeHeight).coerceIn(0f, 1f)
            moved(fromLeft, fromTop)
        }
    }

    private companion object {
        const val TAG = "nuke-autoplay"
        const val START_LEFT = 1f
        const val START_TOP = 0.45f
        const val SIZE_DP = 44
        const val MARGIN_DP = 8
        const val ELEVATION = 8f
        const val ON_ALPHA = 0.9f
        const val OFF_ALPHA = 0.45f
    }
}
