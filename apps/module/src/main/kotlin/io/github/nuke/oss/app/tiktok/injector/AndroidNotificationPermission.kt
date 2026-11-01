package io.github.nuke.oss.app.tiktok.injector

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.tiktok.feature.general.NotificationPermission

internal class AndroidNotificationPermission : NotificationPermission {
    override fun isPendingNotificationRequest(activity: Any, permissions: Array<*>): Boolean {
        val host = activity as? Activity ?: return false
        return permissions.size == 1 && permissions[0] == Manifest.permission.POST_NOTIFICATIONS &&
            host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    // Reports the permission as denied through the Activity's own result callback, unless the Activity is gone.
    override fun deny(activity: Any, requestCode: Int) {
        val owner = (activity as? Activity)?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val callback = Activity::class.resolve().firstMethod {
            name = "onRequestPermissionsResult"
            parameters(Int::class, Array<String>::class, IntArray::class)
        }
        callback.of(owner).invoke(requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED))
    }
}
