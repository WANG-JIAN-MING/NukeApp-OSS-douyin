package io.github.nuke.oss.ui.manager

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.UiAppearance
import io.github.nuke.oss.app.shared.settings.preferencesUiConfigStore
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.status.ActivationReader
import io.github.nuke.oss.status.ModuleStatus
import java.io.File
import kotlin.coroutines.coroutineContext

internal class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val appearance = UiAppearance(preferencesUiConfigStore(application))
    private val mutableState = MutableStateFlow(ManagerUiState(BuildConfig.VERSION_NAME, ui = appearance.read(), backgroundFile = appearance.backgroundFile))
    val state = mutableState.asStateFlow()
    private val messages = Channel<Int>(Channel.BUFFERED)
    val notifications = messages.receiveAsFlow()
    private val context get() = getApplication<Application>()
    private val activation = ActivationReader(application.packageManager)
    private val launcher = ComponentName(application, LAUNCHER_ALIAS)
    private var worker: Job? = null
    private var reading: Job? = null

    init {
        mutableState.update { it.copy(launcherVisible = launcherVisible()) }
        ModuleStatus.listen()
        // A service that binds or dies changes the answer, so each change reads the state again.
        viewModelScope.launch { ModuleStatus.service.collect { refreshActivation() } }
    }

    // Also called whenever the screen resumes: scope and running hosts change in the framework manager meanwhile.
    fun refreshActivation() {
        reading?.cancel()
        reading = viewModelScope.launch(Dispatchers.IO) {
            val read = activation.read()
            mutableState.update { it.copy(activation = read) }
        }
    }

    // Disabling the launcher alias leaves MainActivity reachable from Xposed managers through MODULE_SETTINGS.
    fun setLauncherVisible(visible: Boolean) {
        val setting = if (visible) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(launcher, setting, PackageManager.DONT_KILL_APP)
        mutableState.update { it.copy(launcherVisible = launcherVisible()) }
        messages.trySend(if (visible) R.string.manager_launcher_shown else R.string.manager_launcher_hidden)
    }

    private fun launcherVisible() =
        context.packageManager.getComponentEnabledSetting(launcher) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setUi(config: UiConfig) {
        appearance.write(config)
        mutableState.update { it.copy(ui = config) }
    }

    suspend fun pickBackground(uri: Uri): Boolean {
        val updated = appearance.importBackground(context.contentResolver, uri, state.value.ui) ?: return false
        mutableState.update { it.copy(ui = updated) }
        return true
    }

    fun removeBackground() = mutableState.update { it.copy(ui = appearance.clearBackground(it.ui)) }

    // The same signed APK serves NukePatcher and, renamed to .zip, a root manager.
    fun exportModule(uri: Uri) {
        if (worker?.isActive == true) return
        mutableState.update { it.copy(isExporting = true, error = null) }
        worker = viewModelScope.launch(Dispatchers.IO) {
            try {
                // SAF owns the destination; the signed APK bytes are copied without repacking.
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { destination ->
                    File(context.applicationInfo.sourceDir).inputStream().use { source ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                }
                coroutineContext.ensureActive()
                messages.trySend(R.string.manager_export_module_success)
            } catch (cancelled: CancellationException) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw cancelled
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                mutableState.update { it.copy(error = context.getString(R.string.manager_error_export)) }
                messages.trySend(R.string.manager_export_failure)
            } finally {
                mutableState.update { it.copy(isExporting = false) }
            }
        }
    }

    private companion object {
        // Keep in step with the activity-alias in AndroidManifest.xml.
        const val LAUNCHER_ALIAS = "io.github.nuke.oss.MainActivityLauncher"
    }
}
