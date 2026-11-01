package io.github.nuke.oss.app.tiktok.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Finds Douyin's settings list and builds the row that goes into it. Every host class and member is resolved when
// the feature installs; the Activity callback then only reads fields and walks views.
internal class SettingsLayout(hostClassLoader: ClassLoader) {
    private val itemType = COMMON_ITEM.toClass(hostClassLoader)
    private val newItem = itemType.resolve().firstConstructor { parameters(android.content.Context::class) }
    private val setLeftText: Method = itemType.resolve().firstMethod {
        name = "setLeftText"
        parameters(CharSequence::class)
        superclass()
    }.self.apply { makeAccessible() }
    // A Drawable setter, if this Douyin build has one; otherwise the row's own image view takes the icon.
    private val setLeftIcon: Method? = itemType.resolve().optional(silent = true).firstMethodOrNull {
        name { it.startsWith("setLeftIcon") }
        parameters(Drawable::class)
        superclass()
    }?.self?.apply { makeAccessible() }
    private val scrollTypes = SCROLL_VIEWS.mapNotNull { name ->
        try { name.toClass(hostClassLoader) } catch (_: ClassNotFoundException) { null }
    }

    // The Activity fields that can hold the settings container, read per instance in the callback.
    class Fields internal constructor(val scroll: List<Field>, val linear: List<Field>)

    fun fieldsOf(activityType: Class<*>): Fields {
        // Stop at the framework: its hidden members are neither needed nor reliably reflectable.
        val declared = generateSequence(activityType) { it.superclass }
            .takeWhile { !it.name.startsWith("android.") && !it.name.startsWith("java.") }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .toList()
        return Fields(
            declared.filter { field -> scrollTypes.any { it.isAssignableFrom(field.type) } }.onEach { it.isAccessible = true },
            declared.filter { LinearLayout::class.java.isAssignableFrom(it.type) }.onEach { it.isAccessible = true },
        )
    }

    // Douyin lays the list out as scroll view -> vertical container of CommonItemView rows. Prefer that known shape,
    // then any container field, then whichever vertical container in the view tree holds the most rows.
    fun findList(activity: Activity, fields: Fields): LinearLayout? =
        fromScrollViews(activity, fields) ?: fromLinearFields(activity, fields) ?: fromViewTree(activity)

    private fun fromScrollViews(activity: Activity, fields: Fields): LinearLayout? {
        val candidates = fields.scroll
            .mapNotNull { field -> (field.get(activity) as? ViewGroup)?.let { (field.type in scrollTypes) to it } }
            .distinctBy { it.second }
            .flatMap { (exact, scroll) ->
                (0 until scroll.childCount).mapNotNull { index -> (scroll.getChildAt(index) as? LinearLayout)?.let { exact to it } }
            }
        return candidates.maxWithOrNull(compareBy<Pair<Boolean, LinearLayout>>({ it.first }, { deepRows(it.second) }))
            ?.second?.takeIf { deepRows(it) > 0 }
    }

    private fun fromLinearFields(activity: Activity, fields: Fields): LinearLayout? =
        fields.linear.mapNotNull { it.get(activity) as? LinearLayout }.distinct().richest()

    private fun fromViewTree(activity: Activity): LinearLayout? =
        activity.findViewById<ViewGroup>(android.R.id.content)?.let { verticalLayouts(it) }?.richest()

    private fun List<LinearLayout>.richest(): LinearLayout? =
        maxByOrNull { directRows(it) }?.takeIf { directRows(it) > 0 }

    private fun verticalLayouts(view: View): List<LinearLayout> = buildList {
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(verticalLayouts(view.getChildAt(index)))
    }

    private fun directRows(group: ViewGroup): Int = (0 until group.childCount).count { itemType.isInstance(group.getChildAt(it)) }

    private fun deepRows(group: ViewGroup): Int = directRows(group) +
        (0 until group.childCount).sumOf { (group.getChildAt(it) as? ViewGroup)?.let(::deepRows) ?: 0 }

    // The row sits in its own rounded group so it reads as a separate section, not as another row of the list.
    // Returns whether the icon could be placed; the row works without it.
    fun insertGroup(list: LinearLayout, activity: Activity, label: CharSequence, icon: Drawable, onClick: () -> Unit): Boolean {
        val item = (newItem.create(activity) as View).apply {
            setLeftText.invoke(this, label)
            setOnClickListener { onClick() }
        }
        val iconPlaced = placeIcon(item, icon)
        val group = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.TRANSPARENT)
            val radius = activity.dp(CORNER_DP).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(activity.dp(16f), activity.dp(16f), activity.dp(16f), activity.dp(20f)) }
        list.addView(group, minOf(1, list.childCount), params)
        return iconPlaced
    }

    private fun placeIcon(item: View, icon: Drawable): Boolean {
        setLeftIcon?.let { it.invoke(item, icon); return true }
        val image = firstImage(item) ?: return false
        image.setImageDrawable(icon)
        image.visibility = View.VISIBLE
        return true
    }

    private fun firstImage(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) firstImage(view.getChildAt(index))?.let { return it }
        return null
    }

    fun isInserted(activity: Activity): Boolean =
        activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<View>(TAG) != null

    private fun Activity.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "nuke:tiktok:settings-entry"
        const val CORNER_DP = 8f
        const val COMMON_ITEM = "com.bytedance.ies.dmt.ui.common.views.CommonItemView"
        val SCROLL_VIEWS = listOf(
            "com.ss.android.ugc.aweme.setting.ui.SettingNestedScrollView",
            "androidx.core.widget.NestedScrollView",
            "android.support.v4.widget.NestedScrollView",
        )
    }
}
