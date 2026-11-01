package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.app.shared.script.ScriptRuntime

import android.content.Intent
import java.io.File
import android.os.Binder
import android.os.Bundle
import io.github.nuke.oss.app.shared.diagnostics.DiagnosticsStore
import io.github.nuke.oss.app.shared.environment.EnvironmentInfo
import io.github.nuke.oss.app.shared.environment.ModuleVersion
import io.github.nuke.oss.app.shared.init.SetupStore

// What a host adapter hands to the settings Activity that runs inside the same host process.
internal class HostSettingsSession(
    val host: String,
    private val controller: HostSettingsController,
    val ui: UiConfigStore,
    val setup: SetupStore,
    private val readAccount: () -> HostAccount,
    val contacts: HostContacts,
    val diagnostics: DiagnosticsStore,
    val config: ModuleConfig,
    private val readEnvironment: (ModuleVersion) -> EnvironmentInfo,
    // Null when the host's script store could not be opened; the scripts page says so.
    val scripts: ScriptRuntime? = null,
    val activity: FeatureActivity = FeatureActivity { emptyList() },
    // Null when the host has no protocol sender.
    val protocol: ProtocolConsole? = null,
    // Where a feature's settings keep files, one subdirectory per feature ID; null when the host keeps none.
    val featureFiles: File? = null,
    // Null when the host has no pet feeding.
    val pets: PetConsole? = null,
) {
    fun environment(version: ModuleVersion): EnvironmentInfo = readEnvironment(version)
    fun account(): HostAccount = readAccount()
    fun entries(): List<HostFeature> = controller.entries()
    fun set(featureId: String, enabled: Boolean): List<HostFeature> = controller.set(featureId, enabled)
    fun setOption(featureId: String, key: String, value: Any): List<HostFeature> = controller.setOption(featureId, key, value)
}

// The session travels in the launch Intent as a Binder, which stays a plain object reference inside this process.
// Another app cannot forge one, and after the host process dies the restored Intent carries a dead proxy that
// does not cast, so a recreated screen reads no session instead of a stale one.
private class SessionBinder(val session: HostSettingsSession) : Binder()

internal object HostSettingsHandoff {
    // Named as a string so host adapters never link UI classes.
    const val ACTIVITY = "io.github.nuke.oss.ui.hostsettings.HostSettingsActivity"
    private const val KEY_SESSION = "io.github.nuke.oss.app.shared.settings.SESSION"

    fun intent(stub: Intent, session: HostSettingsSession): Intent =
        stub.putExtras(Bundle().apply { putBinder(KEY_SESSION, SessionBinder(session)) })

    fun sessionOf(intent: Intent?): HostSettingsSession? =
        (intent?.extras?.getBinder(KEY_SESSION) as? SessionBinder)?.session
}
