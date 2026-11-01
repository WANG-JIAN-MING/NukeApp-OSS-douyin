package io.github.nuke.oss.loader.zygisk

import android.util.Log
import io.github.nuke.oss.core.*
import io.github.nuke.oss.backend.lsplant.JniLsplantDriver
import io.github.nuke.oss.backend.lsplant.LsplantBackend
import io.github.nuke.oss.loader.LoaderProfiles
import io.github.nuke.oss.runtime.HostPaths
import java.util.UUID

// Native registers this payload's JNI methods before invoking this entry, after specialize.
object ZygiskEntry {
    // Zygote redirects System.out/err to logcat only after specialization returns to Java, so log directly.
    private const val TAG = "NukeApp-OSS/Zygisk"
    private var bootstrap: ZygiskBootstrap? = null

    // Native passes the nice name it already matched against the app data directory. /proc/self/cmdline
    // cannot be used here: it still names the zygote until Java sets argv0 after nativeForkAndSpecialize.
    // nativeDirectory is where native wrote the package's libraries for this process, or null when it had none.
    @JvmStatic @Synchronized fun bootstrap(processName: String, nativeDirectory: String?) {
        if (bootstrap != null) return
        try {
            start(processName, nativeDirectory)
        } catch (error: Throwable) {
            Log.e(TAG, "zygisk_bootstrap_failed process=$processName", error)
            throw error
        }
    }

    private fun start(processName: String, nativeDirectory: String?) {
        val processClass = Class.forName("android.os.Process")
        val pid = processClass.getMethod("myPid").invoke(null) as Int
        val uid = processClass.getMethod("myUid").invoke(null) as Int
        val profile = LoaderProfiles.application()
        // A child process is named <package>:<suffix>; the profile decides which of them are in scope.
        val packageName = processName.substringBefore(':')
        check(profile.isTargetProcess(packageName, processName)) { "Process is outside the host scope" }
        val contextClass = Class.forName("android.content.Context")
        val instrumentation = Class.forName("android.app.Instrumentation")
        // This public lifecycle method completes Application.attach before returning.
        val createApplication = instrumentation.getMethod(
            "newApplication", ClassLoader::class.java, String::class.java, contextClass,
        )
        val getLoader = contextClass.getMethod("getClassLoader")
        val getPackage = contextClass.getMethod("getPackageName")
        val getApplicationInfo = contextClass.getMethod("getApplicationInfo")
        val instance = ZygiskBootstrap(
            profile,
            ProcessIdentity(pid, uid, processName, packageName),
            BuildIdentity(profile.applicationId, profile.buildId, UUID.randomUUID().toString()),
            LsplantBackend(), JniLsplantDriver::initialize, profile.createBootstrap(),
            Logger { Log.i(TAG, it) },
            {
                check(getPackage.invoke(it) == packageName) { "Unexpected host Context package" }
                getLoader.invoke(it) as ClassLoader
            },
            {
                val info = getApplicationInfo.invoke(it) as android.content.pm.ApplicationInfo
                HostPaths(info.dataDir, info.sourceDir)
            },
            prepareNatives = { context ->
                // The libraries come from the package itself; the installed APK is looked up only for the signature check.
                try {
                    ModuleNativeLibraries.attach(checkNotNull(ZygiskEntry::class.java.classLoader), nativeDirectory, Logger { Log.i(TAG, it) })
                } catch (error: Exception) {
                    Log.i(TAG, "zygisk_natives_unavailable ${error.javaClass.name}: ${error.message}")
                }
                ModuleNativeLibraries.installedApk(context as android.content.Context, profile.applicationId, profile.buildId)
            },
            exemptHiddenApis = { HiddenApiExemptions.apply(Logger { Log.i(TAG, it) }) },
        )
        bootstrap = instance
        if (instance.start(createApplication)) Log.i(TAG, "zygisk_started process=$processName")
        else Log.i(TAG, "zygisk_inactive process=$processName")
    }
}
