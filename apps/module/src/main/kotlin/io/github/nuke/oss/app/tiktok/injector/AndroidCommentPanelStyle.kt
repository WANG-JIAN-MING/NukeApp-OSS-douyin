package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import io.github.nuke.oss.app.tiktok.feature.comment.CommentPanelStyle
import java.io.File

// Applies the comment page style on every layout pass of the page while it is attached, so comments that load later
// are styled too. Only Views whose current value differs are touched, so a pass that changes nothing causes no redraw.
// A background image becomes the page root's background, scaled to cover it; with transparent on, every View inside is
// cleared so the image shows through. The decoded image is kept for as long as the file stays the same.
internal class AndroidCommentPanelStyle : CommentPanelStyle {
    private var cached: Pair<String, Bitmap>? = null

    override fun apply(page: Any, transparent: Boolean, textColor: Int?, background: File?) {
        val root = page as? View ?: return
        val image = background?.let { decode(it, root) }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { restyle(root, root, transparent, textColor, image) }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                restyle(view, view, transparent, textColor, image)
            }

            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        })
        if (root.isAttachedToWindow) root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        restyle(root, root, transparent, textColor, image)
    }

    private fun restyle(view: View, root: View, transparent: Boolean, textColor: Int?, image: Bitmap?) {
        if (view === root && image != null) {
            if ((view.background as? CoverDrawable)?.bitmap !== image) view.background = CoverDrawable(image)
        } else if (transparent && view.background != null) {
            view.background = null
        }
        if (textColor != null && view is TextView) {
            if (view.currentTextColor != textColor) view.setTextColor(textColor)
            if (view is EditText && view.currentHintTextColor != textColor) view.setHintTextColor(textColor)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) restyle(view.getChildAt(index), root, transparent, textColor, image)
    }

    // Decoded at most at the screen's size, so a large photo does not hold a full-size bitmap.
    private fun decode(file: File, view: View): Bitmap? {
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val metrics = view.resources.displayMetrics
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= metrics.widthPixels && bounds.outHeight / (sample * 2) >= metrics.heightPixels) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cached = key to bitmap
        return bitmap
    }

    // Scales the image to cover the bounds and centres it, cropping what does not fit.
    private class CoverDrawable(val bitmap: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val area = bounds
            if (area.isEmpty) return
            val scale = maxOf(area.width().toFloat() / bitmap.width, area.height().toFloat() / bitmap.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(area.left + (area.width() - bitmap.width * scale) / 2, area.top + (area.height() - bitmap.height * scale) / 2)
            canvas.save()
            canvas.clipRect(area)
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
