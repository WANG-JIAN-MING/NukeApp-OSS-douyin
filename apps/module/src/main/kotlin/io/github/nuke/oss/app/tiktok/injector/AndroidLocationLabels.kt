package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.feed.LocationGradientText
import io.github.nuke.oss.app.tiktok.feature.feed.LocationLabels
import java.util.WeakHashMap
import kotlin.math.ceil

// Styles the location run of the host's time label with a gradient span and takes it off again. The label is found by
// its accessibility text, which the host's binder writes as the publish time prefix plus the exact text, so no resource
// ID of a particular version is needed. Tracked labels are weak and restored on the main thread when the feature closes.
internal class AndroidLocationLabels : LocationLabels {
    private val decorated = WeakHashMap<TextView, Boolean>()

    override fun clear(root: Any) {
        (root as? View)?.let(::textViews)?.forEach(::clearGradient)
    }

    override fun decorate(root: Any, location: String, startColor: Int, endColor: Int): Boolean {
        val view = root as? View ?: return false
        val matches = textViews(view).mapNotNull { label ->
            val text = label.text ?: return@mapNotNull null
            if (label.contentDescription?.toString() != "发布时间：$text") return@mapNotNull null
            LocationGradientText.regionRange(text, location)?.let { label to it }
        }
        val (label, range) = matches.singleOrNull() ?: return false
        val styled = SpannableString(label.text)
        styled.setSpan(GradientSpan(startColor, endColor), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Tracked first, so a setter that fails halfway can still be restored.
        synchronized(decorated) { decorated[label] = true }
        label.text = styled
        return true
    }

    override fun restoreAll() {
        val labels = synchronized(decorated) { decorated.keys.toList().also { decorated.clear() } }
        Handler(Looper.getMainLooper()).post { labels.forEach(::clearGradient) }
    }

    private fun textViews(root: View): List<TextView> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun clearGradient(label: TextView) {
        val text = label.text as? Spanned
        val spans = text?.getSpans(0, text.length, GradientSpan::class.java)
        if (!spans.isNullOrEmpty()) {
            val restored = SpannableString(text)
            spans.forEach(restored::removeSpan)
            label.text = restored
        }
        synchronized(decorated) { decorated.remove(label) }
    }

    // A shader confined to one run of text; the label's shared paint is put back after each draw.
    private class GradientSpan(private val startColor: Int, private val endColor: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            return ceil(paint.measureText(text, start, end)).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val width = paint.measureText(text, start, end)
            if (width <= 0f) return
            val original = paint.shader
            try {
                paint.shader = LinearGradient(x, 0f, x + width, 0f, startColor, endColor, Shader.TileMode.CLAMP)
                canvas.drawText(text, start, end, x, y.toFloat(), paint)
            } finally {
                paint.shader = original
            }
        }
    }
}
