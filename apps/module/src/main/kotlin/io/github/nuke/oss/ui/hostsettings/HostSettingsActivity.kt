package io.github.nuke.oss.ui.hostsettings

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.app.shared.activity.ModuleResourcesContext
import io.github.nuke.oss.app.shared.activity.restartHost
import io.github.nuke.oss.app.shared.settings.HostSettingsHandoff
import io.github.nuke.oss.ui.hostsettings.script.ScriptsModel
import kotlinx.coroutines.launch

// Not declared in any manifest: it is created inside a host process by ModuleActivityHook while the system runs
// one of the host's own Activities as a stub. The stub's manifest entry supplies the task and window, so this class
// takes its theme and resources from the module instead of the host.
class HostSettingsActivity : ComponentActivity() {
    private var moduleResources: Resources? = null
    private val resourcesReady get() = moduleResources != null

    private val settings by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer { HostSettingsViewModel(HostSettingsHandoff.sessionOf(intent)) }
        })[HostSettingsViewModel::class.java]
    }

    private val scripts by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer { ScriptsModel(HostSettingsHandoff.sessionOf(intent)?.scripts) }
        })[ScriptsModel::class.java]
    }

    // The document picker answers this Activity, which runs in the host process, so the file is written and read
    // with the host's grant to it. Registered before onCreate, as the result APIs require.
    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { lifecycleScope.launch { settings.exportConfig(contentResolver, it) } }
    }
    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { lifecycleScope.launch { settings.importConfig(contentResolver, it) } }
    }
    private val exportFailures = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { lifecycleScope.launch { settings.exportFailures(contentResolver, it) } }
    }

    override fun attachBaseContext(newBase: Context) {
        val module = ModuleResourcesContext.create(newBase, BuildConfig.APPLICATION_ID, MODULE_THEME)
        moduleResources = module?.resources
        super.attachBaseContext(module ?: newBase)
    }

    // Host frameworks may replace this Activity's base context or resources after attach (Douyin's do), which makes
    // module resource IDs resolve against the host's table. The module's own Resources are pinned here instead.
    override fun getResources(): Resources = moduleResources ?: super.getResources()
    override fun getAssets(): AssetManager = moduleResources?.assets ?: super.getAssets()

    // The stub's theme IDs belong to the host's resources and do not resolve against the module's.
    override fun setTheme(resid: Int) = super.setTheme(MODULE_THEME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!resourcesReady) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            var query by rememberSaveable { mutableStateOf("") }
            HostSettingsScreen(
                state = settings.state.collectAsStateWithLifecycle().value,
                query = query,
                onQueryChange = { query = it },
                onToggle = settings::setEnabled,
                onOptions = settings::setOptions,
                loadContacts = settings::contacts,
                loadActivity = settings::activity,
                protocol = settings.protocol,
                pets = settings.pets,
                featureFiles = settings.featureFiles,
                onUiChange = settings::setUi,
                onBugReports = settings::setBugReports,
                onPickBackground = { uri -> settings.pickBackground(contentResolver, uri) },
                onRemoveBackground = settings::removeBackground,
                onUnlock = settings::unlock,
                onCleanConfig = {
                    settings.cleanConfig()
                    restartHost(this)
                },
                onExportConfig = { exportConfig.launch(configFileName()) },
                // With nothing to report the picker is not opened; the screen says so instead of writing an empty file.
                onExportFailures = { if (settings.hasFailures()) exportFailures.launch(failuresFileName()) else settings.noFailures() },
                onImportConfig = { importConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                onDismissConfigNotice = settings::dismissConfigNotice,
                onSafeMode = settings::setSafeMode,
                onRestartHost = { restartHost(this) },
                onClose = ::finish,
                scripts = scripts,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (resourcesReady) settings.refresh()
    }

    // A stub that declares it handles a configuration change would leave the module's Resources on the old
    // configuration; recreating rebuilds them. A stub that does not declare it is recreated by the system anyway.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (resourcesReady) recreate()
    }

    private companion object {
        const val MODULE_THEME = android.R.style.Theme_Material_Light_NoActionBar
    }

    private fun configFileName() = "NukeApp-OSS-config-$packageName-${stamp()}.json"

    private fun failuresFileName() = "NukeApp-OSS-failures-$packageName-${stamp()}.txt"

    private fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
}
