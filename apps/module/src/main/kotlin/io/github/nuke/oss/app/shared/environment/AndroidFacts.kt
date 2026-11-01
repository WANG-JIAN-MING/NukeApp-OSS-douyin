package io.github.nuke.oss.app.shared.environment

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.util.Locale

// Reads the facts of EnvironmentBuilder from the platform. Each read stands on its own: a member this Android does not
// have, or a package the host cannot see, leaves that fact out instead of failing the report.
internal object AndroidFacts {
    fun device() = DeviceFacts(
        brand = Build.BRAND,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        hardware = Build.HARDWARE,
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else null,
        abis = Build.SUPPORTED_ABIS.toList(),
        is64Bit = Process.is64Bit(),
    )

    fun system() = SystemFacts(
        release = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        codename = Build.VERSION.CODENAME,
        securityPatch = Build.VERSION.SECURITY_PATCH,
        display = Build.DISPLAY,
        fingerprint = Build.FINGERPRINT,
        buildType = "${Build.TYPE}/${Build.TAGS}",
        kernel = System.getProperty("os.version"),
        locale = Locale.getDefault().toLanguageTag(),
    )

    // Null when the package manager does not show that package to this process.
    fun packageOf(context: Context, name: String): PackageFacts? = try {
        val info = context.packageManager.getPackageInfo(name, 0)
        PackageFacts(name, info.versionName, info.longVersionCode, info.applicationInfo?.targetSdkVersion)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
