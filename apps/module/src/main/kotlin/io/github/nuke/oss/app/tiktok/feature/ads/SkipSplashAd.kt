package io.github.nuke.oss.app.tiktok.feature.ads

import android.app.Activity
import android.content.Intent
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Opens the main screen from the splash ad screen.
internal fun interface MainScreenLauncher {
    fun open(splash: Any, main: Class<*>)
}

private val activityLauncher = MainScreenLauncher { splash, main ->
    val activity = splash as Activity
    activity.startActivity(Intent(activity, main).setPackage(activity.packageName))
    activity.finish()
}

// When the splash ad screen is created, goes straight to the main screen and closes the splash.
@HookEntry
internal class SkipSplashAd(
    private val hostClassLoader: ClassLoader,
    private val launcher: MainScreenLauncher = activityLauncher,
) : RuntimeFeature {
    override val id = "SkipSplashAd"

    override fun install(scope: FeatureScope) {
        val splash = SPLASH_AD_ACTIVITY.toClass(hostClassLoader)
        val main = MAIN_ACTIVITY.toClass(hostClassLoader)
        splash.declaredMethodsNamed("onCreate").forEachIndexed { index, onCreate ->
            scope.hooks.install(onCreate, HookSpec(
                id = "SkipSplash$index",
                after = { call -> call.receiver?.let { launcher.open(it, main) } },
            ))
        }
    }

    companion object : TikTokFeature {
        private const val SPLASH_AD_ACTIVITY = "com.bytedance.ies.ugc.aweme.commercialize.splash.show.SplashAdActivity"
        private const val MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity"

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(SkipSplashAd(hostClassLoader)) }
    }
}
