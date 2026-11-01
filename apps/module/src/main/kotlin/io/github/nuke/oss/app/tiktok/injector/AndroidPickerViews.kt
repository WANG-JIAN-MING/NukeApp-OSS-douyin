package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import io.github.nuke.oss.app.tiktok.feature.chat.PickerViews

internal class AndroidPickerViews : PickerViews {
    override fun activityOf(view: Any): Any? {
        var current: Context = (view as? View)?.context ?: return null
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return null
    }

    override fun find(root: Any, type: Class<*>): Any? {
        val view = root as? View ?: return null
        if (type.isInstance(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) find(view.getChildAt(index), type)?.let { return it }
        }
        return null
    }
}
