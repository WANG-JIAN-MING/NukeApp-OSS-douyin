package io.github.nuke.oss.app.tiktok.injector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.android.dx.stock.ProxyBuilder
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.tiktok.feature.sharing.FriendPrefixes
import io.github.nuke.oss.app.tiktok.feature.sharing.PrefixGroup
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupAnchor
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupBounds
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupPrefixLayout
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupTapTracker
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.Executors
import java.util.WeakHashMap
import kotlin.math.roundToInt

// The groups live in an item decoration of the host's own RecyclerView: an item offset makes room before the first
// row (or column), the decoration paints them over it, and an item-touch listener reports taps while the list keeps
// scrolling, including drags that start on a group. The host's adapter, its positions and its rows are untouched.
// The decoration is a dexmaker subclass of the host's ItemDecoration, generated once into a code cache under the
// host's data directory; the touch listener is a Proxy. The painted groups are offered to accessibility services as
// custom actions on the list. Each group shows its picture, as in the legacy module: read on a worker thread one at a
// time, decoded small and kept for later panels; until it arrives, or when it cannot be read, the group shows a drawn
// two-person glyph. None of it has been run on a real host.
internal class AndroidFriendPrefixes(
    private val hostClassLoader: ClassLoader,
    private val cacheDirectory: File,
    private val strings: ModuleStrings,
    private val logger: Logger,
) : FriendPrefixes {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // Keys are the host's lists; touched on the main thread and by the accessibility hooks.
    private val attached = Collections.synchronizedMap(WeakHashMap<View, Prefix>())
    private val recycler by lazy { Recycler(hostClassLoader, cacheDirectory) }
    @Volatile private var featureId = "?"
    @Volatile private var active = false
    // Decoded group pictures by PrefixAvatar.key, and the keys being read; main thread only.
    private val pictures = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX_PICTURES
    }
    private val loading = HashSet<String>()
    private val loader by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "nuke-share-avatars").apply { isDaemon = true } } }

    override fun install(scope: FeatureScope, featureId: String) {
        this.featureId = featureId
        val viewMembers = View::class.resolve()
        val describe = viewMembers.firstMethod { name = "onInitializeAccessibilityNodeInfo"; parameters(AccessibilityNodeInfo::class) }.self
        val perform = viewMembers.firstMethod { name = "performAccessibilityAction"; parameters(Int::class, Bundle::class) }.self
        active = true
        scope.own(AutoCloseable {
            active = false
            onMain {
                val all = synchronized(attached) { attached.values.toList().also { attached.clear() } }
                all.forEach { it.remove() }
                pictures.clear()
                loading.clear()
            }
        })
        scope.hooks.install(describe, HookSpec(id = "PrefixDescribe", after = { call ->
            if (call.outcome !is CallOutcome.Returned) return@HookSpec
            val view = call.receiver as? View ?: return@HookSpec
            val info = call.argument(0) as? AccessibilityNodeInfo ?: return@HookSpec
            attached[view]?.describe(info)
        }))
        scope.hooks.install(perform, HookSpec(id = "PrefixPerform", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            val view = call.receiver as? View ?: return@HookSpec
            val action = call.argument(0) as? Int ?: return@HookSpec
            if (attached[view]?.perform(action) == true) call.returnEarly(true)
        }))
    }

    override fun attach(list: Any, groups: List<PrefixGroup>, onTap: (index: Int) -> Unit) {
        val view = list as? ViewGroup ?: return
        onMain {
            if (!active) return@onMain
            try {
                attached.remove(view)?.remove()
                val prefix = Prefix(view, groups, onTap)
                prefix.install()
                attached[view] = prefix
            } catch (failure: ReflectiveOperationException) {
                logger.log("feature=$featureId prefix_failed ${failure.javaClass.simpleName}")
            } catch (failure: RuntimeException) {
                logger.log("feature=$featureId prefix_failed ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun isAttached(list: Any): Boolean = (list as? View)?.let { it.isAttachedToWindow && attached[it]?.enabled() == true } == true

    // Reads each picture not yet decoded on the worker thread and repaints the list when one arrives.
    private fun loadPictures(view: View, groups: List<PrefixGroup>, size: Int) {
        for (avatar in groups.mapNotNull { it.avatar }) {
            if (avatar.key in pictures || !loading.add(avatar.key)) continue
            val list = WeakReference(view)
            loader.execute {
                val bitmap = try {
                    avatar.load()?.let { decodePicture(it, size) }
                } catch (failure: IOException) {
                    null.also { logger.log("feature=$featureId avatar_failed ${failure.javaClass.simpleName}") }
                } catch (failure: HostContactsUnavailable) {
                    null.also { logger.log("feature=$featureId avatar_failed ${failure.javaClass.simpleName}") }
                } catch (failure: RuntimeException) {
                    null.also { logger.log("feature=$featureId avatar_failed ${failure.javaClass.simpleName}") }
                }
                main.post {
                    loading -= avatar.key
                    if (bitmap != null && active) {
                        pictures[avatar.key] = bitmap
                        list.get()?.invalidate()
                    }
                }
            }
        }
    }

    // Decodes at the smallest power-of-two step that still covers size on both sides.
    private fun decodePicture(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun onMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) task() else main.post(task)
    }

    // The host's RecyclerView members, found by their public names in the host's loader.
    private class Recycler(loader: ClassLoader, cacheDirectory: File) {
        private val type = RECYCLER.toClass(loader)
        private val decorationType = "$RECYCLER\$ItemDecoration".toClass(loader)
        val touchType: Class<*> = "$RECYCLER\$OnItemTouchListener".toClass(loader)
        private val managerType = "$RECYCLER\$LayoutManager".toClass(loader)
        private val gridType = GRID.toClass(loader)
        private val members = type.resolve()
        val childPosition: Method = members.firstMethod { name = "getChildAdapterPosition"; parameters(View::class); superclass() }.self
        val layoutManager: Method = members.firstMethod { name = "getLayoutManager"; emptyParameters(); superclass() }.self
        val addDecoration: Method = members.firstMethod { name = "addItemDecoration"; parameters(decorationType); superclass() }.self
        val removeDecoration: Method = members.firstMethod { name = "removeItemDecoration"; parameters(decorationType); superclass() }.self
        val addTouch: Method = members.firstMethod { name = "addOnItemTouchListener"; parameters(touchType); superclass() }.self
        val removeTouch: Method = members.firstMethod { name = "removeOnItemTouchListener"; parameters(touchType); superclass() }.self
        val invalidateDecorations: Method = members.firstMethod { name = "invalidateItemDecorations"; emptyParameters(); superclass() }.self
        private val horizontal: Method = managerType.resolve().firstMethod { name = "canScrollHorizontally"; emptyParameters(); superclass() }.self
        private val spanCount: Method = gridType.resolve().firstMethod { name = "getSpanCount"; emptyParameters(); superclass() }.self
        private val decoration: Class<*>

        init {
            val decorationMembers = decorationType.resolve().optional(silent = true)
            val overrides = (decorationMembers.method { name = "getItemOffsets"; parameterCount = 4 } +
                decorationMembers.method { name = "onDrawOver"; parameterCount = 3 }).map { it.self }
            check(overrides.size == 2) { "Unsupported RecyclerView decoration contract" }
            val moduleLoader = checkNotNull(AndroidFriendPrefixes::class.java.classLoader)
            val bridge = object : ClassLoader(loader) {
                override fun findClass(name: String): Class<*> = moduleLoader.loadClass(name)
            }
            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create the friend prefix code cache" }
            decoration = ProxyBuilder.forClass(decorationType).parentClassLoader(bridge).dexCache(cacheDirectory)
                .onlyMethods(overrides.toTypedArray()).buildProxyClass()
        }

        fun newDecoration(): Any = decoration.resolve().firstConstructor { emptyParameters() }.create()

        fun isHorizontal(manager: Any) = horizontal.invoke(manager) == true

        fun lanes(manager: Any) = if (gridType.isInstance(manager)) (spanCount.invoke(manager) as Int).coerceAtLeast(1) else 1
    }

    private inner class Prefix(list: ViewGroup, private val groups: List<PrefixGroup>, private val onTap: (Int) -> Unit) {
        private val list = WeakReference(list)
        private val api = recycler
        private val density = list.resources.displayMetrics.density
        private val scaled = list.resources.displayMetrics
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pictureMatrix = Matrix()
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        private val taps = ShareGroupTapTracker(ViewConfiguration.get(list.context).scaledTouchSlop.toFloat())
        private val primary = themeColor(list, android.R.attr.textColorPrimary, 0xFF161823.toInt())
        private val secondary = themeColor(list, android.R.attr.textColorSecondary, 0xFF8A8B91.toInt())
        private val members = groups.map { group ->
            strings.get(list.context, R.string.host_share_groups_members)?.let { String.format(it, group.members) } ?: group.members.toString()
        }
        private val actions = groups.map { group ->
            strings.get(list.context, R.string.host_share_groups_action)?.let { String.format(it, group.name, group.members) } ?: group.name
        }
        private val decoration = api.newDecoration()
        private val touch = Proxy.newProxyInstance(api.touchType.classLoader, arrayOf(api.touchType)) { self, method, args ->
            when (method.name) {
                "onInterceptTouchEvent" -> guarded(false) { touch(args!![1] as MotionEvent) }
                "onRequestDisallowInterceptTouchEvent" -> { if (args?.firstOrNull() == true) taps.cancel(); null }
                "equals" -> self === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "NukeShareGroupTouch"
                else -> null
            }
        }
        @Volatile private var installed = false

        fun enabled() = installed && active

        fun install() {
            val view = checkNotNull(list.get())
            ProxyBuilder.setInvocationHandler(decoration, InvocationHandler { proxy, method, values ->
                when (method.name) {
                    "getItemOffsets" -> guarded<Any?>(null) { offsets(values!![0] as Rect, values[1] as View); null }
                    "onDrawOver" -> guarded<Any?>(null) { draw(values!![0] as Canvas); null }
                    else -> ProxyBuilder.callSuper(proxy, method, *(values ?: emptyArray()))
                }
            })
            installed = true
            try {
                api.addDecoration.invoke(view, decoration)
                api.addTouch.invoke(view, touch)
                api.invalidateDecorations.invoke(view)
            } catch (failure: InvocationTargetException) {
                remove()
                throw failure
            }
            loadPictures(view, groups, dp(PICTURE_SIZE).toInt())
        }

        fun remove() {
            installed = false
            taps.cancel()
            val view = list.get() ?: return
            try {
                api.removeDecoration.invoke(view, decoration)
                api.removeTouch.invoke(view, touch)
            } catch (failure: ReflectiveOperationException) {
                logger.log("feature=$featureId prefix_remove_failed ${failure.javaClass.simpleName}")
            }
            view.requestLayout()
            view.invalidate()
        }

        // A failure inside a callback of the host's list turns this prefix off once instead of breaking the host's layout.
        private fun <T> guarded(fallback: T, action: () -> T): T = try {
            action()
        } catch (failure: ReflectiveOperationException) {
            fallback.also { failed(failure) }
        } catch (failure: RuntimeException) {
            fallback.also { failed(failure) }
        }

        private fun failed(failure: Exception) {
            if (!installed) return
            installed = false
            logger.log("feature=$featureId prefix_stopped ${failure.javaClass.simpleName}")
            main.post { list.get()?.let { attached.remove(it) }; remove() }
        }

        private fun dp(value: Int) = value * density
        private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, scaled)

        private fun layout(view: View): ShareGroupPrefixLayout {
            val manager = checkNotNull(api.layoutManager.invoke(view)) { "The list has no layout manager" }
            return ShareGroupPrefixLayout(api.isHorizontal(manager), api.lanes(manager), groups.size, dp(CELL_WIDTH), dp(CELL_HEIGHT))
        }

        private fun position(view: View, child: View) = (api.childPosition.invoke(view, child) as Number).toInt()

        private fun offsets(rect: Rect, child: View) {
            rect.set(0, 0, 0, 0)
            val view = list.get() ?: return
            if (!enabled()) return
            val layout = layout(view)
            val inset = layout.inset(position(view, child)).roundToInt()
            if (layout.horizontal) rect.left = inset else rect.top = inset
        }

        private fun bounds(view: ViewGroup): List<ShareGroupBounds> {
            val layout = layout(view)
            val anchors = (0 until view.childCount).mapNotNull { index ->
                val child = view.getChildAt(index)
                val position = position(view, child)
                if (position in 0 until layout.lanes) ShareGroupAnchor(position, child.x, child.y, child.width.toFloat()) else null
            }
            return layout.bounds(anchors)
        }

        private fun draw(canvas: Canvas) {
            val view = list.get() ?: return
            if (!enabled()) return
            val saved = canvas.save()
            try {
                canvas.clipRect(view.paddingLeft, view.paddingTop, view.width - view.paddingRight, view.height - view.paddingBottom)
                for (cell in bounds(view)) {
                    val group = groups[cell.index]
                    val cx = cell.left + cell.width / 2f
                    val cy = cell.top + dp(28)
                    val picture = group.avatar?.key?.let(pictures::get)
                    if (picture != null) {
                        drawPicture(canvas, picture, cx, cy, dp(27))
                    } else {
                        paint.color = ACCENT
                        paint.alpha = 31
                        canvas.drawCircle(cx, cy, dp(27), paint)
                        // Two people, the shape of the host's round avatars.
                        paint.alpha = 255
                        canvas.drawCircle(cx - dp(7), cy - dp(5), dp(5), paint)
                        canvas.drawRoundRect(cx - dp(17), cy + dp(2), cx + dp(2), cy + dp(13), dp(6), dp(6), paint)
                        paint.alpha = 165
                        canvas.drawCircle(cx + dp(8), cy - dp(5), dp(5), paint)
                        canvas.drawRoundRect(cx + dp(1), cy + dp(2), cx + dp(18), cy + dp(13), dp(6), dp(6), paint)
                    }
                    text.color = primary
                    text.textSize = sp(12f)
                    canvas.drawText(TextUtils.ellipsize(group.name, text, cell.width - dp(6), TextUtils.TruncateAt.END).toString(), cx, cell.top + dp(76), text)
                    text.color = secondary
                    text.textSize = sp(10f)
                    canvas.drawText(TextUtils.ellipsize(members[cell.index], text, cell.width - dp(6), TextUtils.TruncateAt.END).toString(), cx, cell.top + dp(92), text)
                }
            } finally {
                canvas.restoreToCount(saved)
            }
        }

        // The picture cropped to the circle of the host's own round avatars.
        private fun drawPicture(canvas: Canvas, picture: Bitmap, cx: Float, cy: Float, radius: Float) {
            val scale = maxOf(radius * 2 / picture.width, radius * 2 / picture.height)
            pictureMatrix.setScale(scale, scale)
            pictureMatrix.postTranslate(cx - picture.width * scale / 2, cy - picture.height * scale / 2)
            picturePaint.shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(pictureMatrix) }
            canvas.drawCircle(cx, cy, radius, picturePaint)
        }

        // Answers true only for the release that completes a tap on a group; the list keeps every other event.
        private fun touch(event: MotionEvent): Boolean {
            val view = list.get() ?: return false
            if (!enabled()) {
                taps.cancel()
                return false
            }
            val inside = event.x >= view.paddingLeft && event.x < view.width - view.paddingRight &&
                event.y >= view.paddingTop && event.y < view.height - view.paddingBottom
            val hit = if (inside) bounds(view).firstOrNull { it.contains(event.x, event.y) }?.index else null
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> taps.down(hit, event.x, event.y)
                MotionEvent.ACTION_MOVE -> taps.move(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    val index = taps.up(hit, event.x, event.y) ?: return false
                    onTap(index)
                    return true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> taps.cancel()
            }
            return false
        }

        fun describe(info: AccessibilityNodeInfo) {
            if (!enabled()) return
            actions.forEachIndexed { index, label -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_BASE + index, label)) }
        }

        fun perform(action: Int): Boolean {
            val index = action - ACTION_BASE
            if (!enabled() || index !in groups.indices) return false
            onTap(index)
            return true
        }
    }

    private fun themeColor(view: View, attribute: Int, fallback: Int): Int {
        val values = view.context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getColor(0, fallback)
        } finally {
            values.recycle()
        }
    }

    private companion object {
        const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        const val GRID = "androidx.recyclerview.widget.GridLayoutManager"
        const val ACTION_BASE = 0x790f7000
        const val CELL_WIDTH = 76
        const val CELL_HEIGHT = 100
        const val PICTURE_SIZE = 54
        const val MAX_PICTURES = 40
        // The host's brand red.
        const val ACCENT = 0xFFFE2C55.toInt()
    }
}
