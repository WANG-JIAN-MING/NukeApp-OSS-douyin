package io.github.nuke.oss.loader.zygisk

import android.content.Context
import io.github.nuke.oss.core.Logger

// The payload loader is built from memory without a library path, so System.loadLibrary from module code (DexKit, the
// protocol core, the audio codec) would find nothing in Zygisk mode. The native module copies the libraries that the root
// package carries into a directory of the host's own code_cache, and this adds that directory to the loader. They go in
// through BaseDexClassLoader.addNativePath, a framework method outside the public SDK, before any payload library is
// loaded, so the loader's linker namespace is created with them. The libraries come from the same package as the payload,
// so they always match its JNI; no installed module APK is needed.
internal object ModuleNativeLibraries {
    fun attach(loader: ClassLoader, directory: String?, logger: Logger) {
        checkNotNull(directory) { "The Zygisk package carries no native libraries, or they could not be written to the host" }
        val addNativePath = Class.forName("dalvik.system.BaseDexClassLoader").getMethod("addNativePath", Collection::class.java)
        addNativePath.invoke(loader, listOf(directory))
        logger.log("zygisk_natives_ready")
    }

    // The installed module APK of this build, which only the module signature check needs; null when it is not installed,
    // not visible to the host, or another build. Nothing else in the process depends on it being there.
    fun installedApk(context: Context, moduleId: String, buildId: String): String? {
        val installed = try {
            context.packageManager.getPackageInfo(moduleId, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
        if (installed.versionName != buildId) return null
        return installed.applicationInfo?.sourceDir
    }
}
