package io.github.nuke.oss.ui.hostsettings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.diagnostics.FailureStore
import io.github.nuke.oss.app.shared.settings.ActivityRecord
import io.github.nuke.oss.app.shared.settings.ConfigImportRejected
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.FeatureDisplay
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.HostSettingsSession
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.UiAppearance
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.display
import io.github.nuke.oss.app.shared.settings.refreshedBy
import java.io.File
import java.io.IOException
import io.github.nuke.oss.app.shared.environment.ModuleVersion

// LIVE: running inside the host process that owns the session. UNAVAILABLE: no session came with the launch, for
// example the screen was restored after the host process was killed; reopen it from the host's settings.
internal enum class HostSession { LIVE, UNAVAILABLE }

internal data class HostSettingsUiState(
    val host: String?,
    val features: List<HostFeature>,
    val session: HostSession,
    val ui: UiConfig = UiConfig(),
    val backgroundFile: File? = null,
    // Null while the first-launch setup has not asked; it reads as off.
    val bugReports: Boolean? = null,
    val account: HostAccount = HostAccount.Unavailable,
    // The last failure of each feature, keyed by feature ID; a feature listed here is locked until it is unlocked.
    val failures: Map<String, FailureRecord> = emptyMap(),
    val safeMode: Boolean = false,
    // The outcome of the last configuration or failure export, or configuration import, until the dialog that shows it is
    // dismissed.
    val configNotice: ConfigNotice? = null,
    // The features waiting for a restart when the screen opened. Leaving asks for a restart only when something is waiting
    // that was not already, so a change from an earlier visit is not asked about again, and switching a change back
    // leaves nothing to ask about.
    val restartBaseline: Set<String> = emptySet(),
) {
    val restartNeeded: Boolean get() = restartPending(features).let { it.isNotEmpty() && it != restartBaseline }
}

// The features whose stored choice is not what the running host does: a switch that differs from the running state, or
// an option saved with a value other than the one the feature was built with.
internal fun restartPending(features: List<HostFeature>): Set<String> = features.filter { feature ->
    feature.display().let { it == FeatureDisplay.ON_AFTER_RESTART || it == FeatureDisplay.OFF_AFTER_RESTART } ||
        feature.options.any { it.pending }
}.map { it.id }.toSet()

internal sealed interface ConfigNotice {
    data object Exported : ConfigNotice
    data object ExportFailed : ConfigNotice
    data class Imported(val count: Int) : ConfigNotice
    data object ReadFailed : ConfigNotice
    data class Rejected(val reason: ConfigImportRejected.Reason, val detail: String?) : ConfigNotice
    data class FailuresExported(val count: Int) : ConfigNotice
    data object NoFailures : ConfigNotice
    data object FailuresExportFailed : ConfigNotice
}

// Runs on the host main thread, in the same process as the features. Switches are written straight to the host's
// private storage; the running features keep their state until the host restarts.
internal class HostSettingsViewModel(private val session: HostSettingsSession?) : ViewModel() {
    private val appearance = session?.let { UiAppearance(it.ui) }
    private val mutableState = MutableStateFlow(
        if (session == null) HostSettingsUiState(null, emptyList(), HostSession.UNAVAILABLE)
        else session.entries().let { entries ->
            HostSettingsUiState(
                session.host, entries, HostSession.LIVE, UiAppearance(session.ui).read(), session.ui.backgroundFile,
                session.setup.bugReports(), session.account(),
                session.diagnostics.failures(), session.diagnostics.safeMode(),
                restartBaseline = restartPending(entries),
            )
        },
    )
    val state = mutableState.asStateFlow()

    // While this screen was in the background the host may have switched or signed out the account, and a running
    // feature may have failed and been locked.
    fun refresh() {
        val target = session ?: return
        val next = target.account()
        mutableState.update {
            it.copy(account = it.account.refreshedBy(next), features = target.entries(), failures = target.diagnostics.failures())
        }
    }

    // Clearing a record unlocks the feature: from the next host start it loads again by its own switch.
    fun unlock(featureId: String) {
        val target = session ?: return
        target.diagnostics.clear(featureId)
        mutableState.update { it.copy(failures = target.diagnostics.failures()) }
    }

    // Resets every preference and record of the module in this host; the caller restarts the host, since running
    // features and this screen still hold the old values.
    fun cleanConfig() {
        session?.config?.clean()
    }

    suspend fun exportConfig(resolver: ContentResolver, uri: Uri) {
        val target = session ?: return
        val notice = withContext(Dispatchers.IO) {
            try {
                val text = target.config.export()
                checkNotNull(resolver.openOutputStream(uri, "wt")) { "No output stream" }.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ConfigNotice.Exported
            } catch (_: IOException) {
                failedExport(resolver, uri)
            } catch (_: IllegalStateException) {
                failedExport(resolver, uri)
            } catch (_: SecurityException) {
                failedExport(resolver, uri)
            }
        }
        mutableState.update { it.copy(configNotice = notice) }
    }

    // Whether there is anything for the failure report; the screen asks before it opens the document picker.
    fun hasFailures(): Boolean {
        val target = session ?: return false
        return FailureReport.count(target.entries(), target.diagnostics.failures()) > 0
    }

    fun noFailures() = mutableState.update { it.copy(configNotice = ConfigNotice.NoFailures) }

    // Every failure record, and every feature that failed to run without one, as one text file.
    suspend fun exportFailures(resolver: ContentResolver, uri: Uri) {
        val target = session ?: return
        val features = target.entries()
        val failures = target.diagnostics.failures()
        val safeMode = mutableState.value.safeMode
        val notice = withContext(Dispatchers.IO) {
            try {
                // Package lookups and the Dex counts are read here, off the main thread.
                val environment = target.environment(ModuleVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()))
                val text = FailureReport.build(environment, features, failures, safeMode, System.currentTimeMillis())
                checkNotNull(resolver.openOutputStream(uri, "wt")) { "No output stream" }.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ConfigNotice.FailuresExported(FailureReport.count(features, failures))
            } catch (_: IOException) {
                failedExport(resolver, uri, ConfigNotice.FailuresExportFailed)
            } catch (_: IllegalStateException) {
                failedExport(resolver, uri, ConfigNotice.FailuresExportFailed)
            } catch (_: SecurityException) {
                failedExport(resolver, uri, ConfigNotice.FailuresExportFailed)
            }
        }
        mutableState.update { it.copy(configNotice = notice) }
    }

    // Writes nothing unless the whole file is a valid configuration for this host. The running features keep their
    // state until the host restarts; this screen shows the imported values at once.
    suspend fun importConfig(resolver: ContentResolver, uri: Uri) {
        val target = session ?: return
        val text = withContext(Dispatchers.IO) { readConfig(resolver, uri) }
            ?: return mutableState.update { it.copy(configNotice = ConfigNotice.ReadFailed) }
        val notice = try {
            ConfigNotice.Imported(target.config.import(text))
        } catch (rejected: ConfigImportRejected) {
            ConfigNotice.Rejected(rejected.reason, rejected.detail)
        }
        mutableState.update {
            it.copy(
                configNotice = notice, features = target.entries(), ui = UiAppearance(target.ui).read(),
                bugReports = target.setup.bugReports(),
            )
        }
    }

    fun dismissConfigNotice() = mutableState.update { it.copy(configNotice = null) }

    // Takes effect at the next host start; turning it on restarts the host from the screen.
    fun setSafeMode(enabled: Boolean) {
        val target = session ?: return
        target.diagnostics.setSafeMode(enabled)
        mutableState.update { it.copy(safeMode = enabled) }
    }

    fun setEnabled(featureId: String, enabled: Boolean) {
        val target = session ?: return
        val features = try {
            target.set(featureId, enabled)
        } catch (_: IllegalArgumentException) {
            target.entries()
        }
        mutableState.update { it.copy(features = features) }
    }

    fun setOption(featureId: String, key: String, value: Any) {
        val target = session ?: return
        val features = try {
            target.setOption(featureId, key, value)
        } catch (_: IllegalArgumentException) {
            target.entries()
        }
        mutableState.update { it.copy(features = features) }
    }

    // What an options dialog saves: every changed option of one feature, written together.
    fun setOptions(featureId: String, values: Map<String, Any>) {
        val target = session ?: return
        var features = state.value.features
        for ((key, value) in values) {
            features = try {
                target.setOption(featureId, key, value)
            } catch (_: IllegalArgumentException) {
                target.entries()
            }
        }
        mutableState.update { it.copy(features = features) }
    }

    // The host's friends and groups for a chat picker, read off the main thread. Throws HostContactsUnavailable
    // with the reason when there is no session or the host cannot answer.
    suspend fun contacts(): HostContactList {
        val target = session ?: throw HostContactsUnavailable("No session")
        return withContext(Dispatchers.IO) { target.contacts.load() }
    }

    // The host's protocol sender, when it has one.
    val protocol: ProtocolConsole? get() = session?.protocol

    // The host's pet feeding, when it has one.
    val pets: PetConsole? get() = session?.pets

    // Where features' settings keep files in the host, when it keeps any.
    val featureFiles: File? get() = session?.featureFiles

    // What a feature has done lately, for its options dialog, read off the main thread. Throws when there is no
    // session or the record does not read, with the reason as the message.
    suspend fun activity(featureId: String): List<ActivityRecord> {
        val target = session ?: throw IllegalStateException("No session")
        return withContext(Dispatchers.IO) { target.activity.read(featureId) }
    }

    fun setBugReports(enabled: Boolean) {
        val target = session ?: return
        target.setup.setBugReports(enabled)
        mutableState.update { it.copy(bugReports = enabled) }
    }

    fun setUi(config: UiConfig) {
        val target = appearance ?: return
        target.write(config)
        mutableState.update { it.copy(ui = config) }
    }

    suspend fun pickBackground(resolver: ContentResolver, uri: Uri): Boolean {
        val target = appearance ?: return false
        val updated = target.importBackground(resolver, uri, state.value.ui) ?: return false
        mutableState.update { it.copy(ui = updated) }
        return true
    }

    fun removeBackground() {
        val target = appearance ?: return
        mutableState.update { it.copy(ui = target.clearBackground(it.ui)) }
    }
}

private fun FailureStore.failures(): Map<String, FailureRecord> = read().associateBy { it.featureId }

// A half-written destination is removed when the provider allows it; the export is reported as failed either way.
private fun failedExport(resolver: ContentResolver, uri: Uri, notice: ConfigNotice = ConfigNotice.ExportFailed): ConfigNotice {
    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    return notice
}

// A configuration is a small text file; anything larger is not one, and a file that cannot be opened reads as null.
private fun readConfig(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MAX_CONFIG_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        if (out.size() > MAX_CONFIG_BYTES) null else out.toString(Charsets.UTF_8.name())
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private const val MAX_CONFIG_BYTES = 1024 * 1024
