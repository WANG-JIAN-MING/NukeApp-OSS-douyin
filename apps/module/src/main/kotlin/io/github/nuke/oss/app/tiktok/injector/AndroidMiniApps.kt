package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.tiktok.feature.miniapp.DialogArguments
import io.github.nuke.oss.app.tiktok.feature.miniapp.RealNameDialogs
import io.github.nuke.oss.app.tiktok.feature.miniapp.RewardAdUi
import org.json.JSONObject

// The Android parts of the two mini-app features: the real-name dialog's views and the reward video's JSON and focus.
internal class AndroidMiniApps : RealNameDialogs, RewardAdUi {
    override fun arguments(dialog: Any): DialogArguments? {
        val bundle = dialog.callByName("getArguments") as? Bundle ?: return null
        return DialogArguments(bundle.getString("KEY_FROM"), bundle.getByte("key_close_setting", 0.toByte()).toInt())
    }

    // The close button is found by the view's name in the host's resources, so no resource number is pinned.
    override fun closeButton(dialog: Any): Any? {
        val activity = dialog.callByName("getActivity") as? Activity ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        val window = dialog.callByName("getDialog") as? Dialog ?: return null
        if (!window.isShowing) return null
        val root = dialog.callByName("getView") as? View ?: return null
        val id = root.resources.getIdentifier("ij6", "id", root.context.packageName)
        if (id == 0) return null
        val button = root.findViewById<View>(id) ?: return null
        return button.takeIf { it.visibility == View.VISIBLE && it.isShown && it.isAttachedToWindow && it.isEnabled && it.hasOnClickListeners() }
    }

    override fun press(button: Any): Boolean = (button as? View)?.performClick() == true

    override fun isForeground(activity: Any?): Boolean {
        val current = activity as? Activity ?: return false
        return !current.isFinishing && !current.isDestroyed && current.hasWindowFocus()
    }

    override fun emptyJson(): Any = JSONObject()

    // The close event the SDK's own ad sends: mode "ad" and a count of one. No watch time is made up.
    override fun closeJson(): Any = JSONObject().put("mode", "ad").put("count", 1)
}
