package io.github.nuke.oss.loader.zygisk

import android.os.Build
import io.github.nuke.oss.core.Logger
import org.lsposed.hiddenapibypass.LSPass

// Xposed frameworks load modules with framework privileges; Zygisk runs the payload in the host's app domain, where the
// framework members below are hidden APIs that a later release may block. LSPass (LSPosed's AndroidHiddenApiBypass,
// the variant that reaches VMRuntime.setHiddenApiExemptions through Property.of instead of Unsafe) exempts exactly these
// classes. The exemption is process-wide, so the host's own code gains the same access to them; nothing wider is
// exempted. Before Android 9 there is no restriction to lift.
internal object HiddenApiExemptions {
    // BaseDexClassLoader.addNativePath (native libraries), ActivityThread.currentActivityThread and mInstrumentation
    // (module screens in the host), Activity.dispatchActivityResult (the file picker).
    val PREFIXES = arrayOf("Ldalvik/system/BaseDexClassLoader;", "Landroid/app/ActivityThread;", "Landroid/app/Activity;")

    fun apply(logger: Logger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // LSPass says whether VMRuntime accepted the list; it may be blocked like any meta-reflection.
        val exempted = LSPass.addHiddenApiExemptions(*PREFIXES)
        logger.log(if (exempted) "zygisk_hidden_api_exempted classes=${PREFIXES.size}" else "zygisk_hidden_api_exemption_refused")
    }
}
