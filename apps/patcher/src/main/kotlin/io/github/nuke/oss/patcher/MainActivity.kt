package io.github.nuke.oss.patcher

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.nuke.oss.patcher.ui.PatcherScreen
import io.github.nuke.oss.patcher.ui.PatcherViewModel

class MainActivity : ComponentActivity() {
    private val patcher by lazy { ViewModelProvider(this)[PatcherViewModel::class.java] }
    private val selectTarget = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(patcher::importTarget)
    }
    private val selectModule = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(patcher::importModule)
    }
    private val exportPatched = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        uri?.let(patcher::exportPatched)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            patcher.notifications.collect { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
        }
        setContent {
            val state = patcher.state.collectAsStateWithLifecycle().value
            PatcherScreen(
                state = state,
                onSelectApk = { selectTarget.launch(APK_TYPES) },
                onSelectModule = { selectModule.launch(APK_TYPES) },
                onPatch = patcher::patch,
                onExportPatchedApk = { exportPatched.launch(state.outputName ?: "nuke-patched.apk") },
            )
        }
    }

    companion object {
        private val APK_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip")
    }
}
