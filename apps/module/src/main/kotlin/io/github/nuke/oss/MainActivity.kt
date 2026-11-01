package io.github.nuke.oss

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.nuke.oss.app.shared.integrity.ModuleSignature
import io.github.nuke.oss.ui.manager.ManagerScreen
import io.github.nuke.oss.ui.manager.ManagerViewModel
import kotlinx.coroutines.launch

// Opened from the launcher alias and, with the icon hidden, from Xposed managers through MODULE_SETTINGS.
class MainActivity : ComponentActivity() {
    private val manager by lazy { ViewModelProvider(this)[ManagerViewModel::class.java] }
    private val exportModule = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        uri?.let(manager::exportModule)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The module's own app does not run from an APK another certificate signed.
        ModuleSignature.refusal(applicationInfo.sourceDir, { Log.i(SIGNATURE_TAG, it) })?.let { reason ->
            Log.e(SIGNATURE_TAG, reason)
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
            return
        }
        enableEdgeToEdge()
        lifecycleScope.launch {
            manager.notifications.collect(::toast)
        }
        // The scope and the loaded hosts change in the framework manager while this screen is away.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { manager.refreshActivation() }
        }
        setContent {
            val state = manager.state.collectAsStateWithLifecycle().value
            ManagerScreen(
                state,
                onExportModule = { exportModule.launch("NukeApp-OSS-${BuildConfig.VERSION_NAME}.apk") },
                onOpenSource = ::openSource,
                onLauncherVisibleChange = manager::setLauncherVisible,
                onUiChange = manager::setUi,
                onPickBackground = manager::pickBackground,
                onRemoveBackground = manager::removeBackground,
            )
        }
    }

    private fun openSource() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.manager_source_url).toUri()))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.manager_link_failed)
        }
    }

    private fun toast(message: Int) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
}

private const val SIGNATURE_TAG = "NukeApp-OSS/Signature"
