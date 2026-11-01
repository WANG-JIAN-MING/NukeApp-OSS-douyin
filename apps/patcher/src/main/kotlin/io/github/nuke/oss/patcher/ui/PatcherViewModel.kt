package io.github.nuke.oss.patcher.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
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
import io.github.nuke.oss.patcher.BuildConfig
import io.github.nuke.oss.patcher.R
import io.github.nuke.oss.patcher.patch.ApkRole
import io.github.nuke.oss.patcher.patch.LocalPatchKey
import io.github.nuke.oss.patcher.patch.NativePatchFiles
import io.github.nuke.oss.patcher.patch.PatchEngine
import io.github.nuke.oss.patcher.patch.PatchInputs
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal class PatcherViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(PatcherUiState(BuildConfig.VERSION_NAME))
    val state = mutableState.asStateFlow()
    private val messages = Channel<Int>(Channel.BUFFERED)
    val notifications = messages.receiveAsFlow()
    private val context get() = getApplication<Application>()
    private val workspace = File(context.cacheDir, "patcher/${UUID.randomUUID()}")
    private val inputs = PatchInputs()
    private var worker: Job? = null
    private val cleared = AtomicBoolean(false)

    fun importTarget(uri: Uri) = importApk(uri, ApkRole.TARGET)
    fun importModule(uri: Uri) = importApk(uri, ApkRole.MODULE)

    private fun importApk(uri: Uri, role: ApkRole) = work {
        inputs.invalidate(role)
        File(workspace, "output").deleteRecursively()
        mutableState.update {
            val invalidated = if (role == ApkRole.TARGET) {
                it.copy(selectedApkName = null, selectedPackageName = null, selectedVersionName = null, targetSha256 = null)
            } else {
                it.copy(moduleApkName = null, moduleVersionName = null, moduleSha256 = null)
            }
            invalidated.copy(phase = PatchPhase.IMPORTING, outputName = null, outputSha256 = null,
                error = null, progressMessage = null, logLines = emptyList())
        }
        check(workspace.isDirectory || workspace.mkdirs())
        val file = File(workspace, if (role == ApkRole.TARGET) "target.apk" else "module.apk")
        file.delete()
        try {
            NativePatchFiles.ensureLoaded()
            val report = context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                checkNotNull(descriptor)
                verifyResult(NativePatchFiles.stageApk(descriptor.fd, file.path))
            }
            require(!report.getBoolean("already_patched")) { "already_patched" }
            coroutineContext.ensureActive()
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.take(160) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: file.name
            val selected = inputs.replace(role, file, name, report.getString("sha256"))
            require(selected.metadata.minSdk <= Build.VERSION.SDK_INT) { "minimum_sdk" }
            coroutineContext.ensureActive()
            mutableState.update {
                it.copy(selectedApkName = inputs.target?.displayName, selectedPackageName = inputs.target?.metadata?.packageName,
                    selectedVersionName = inputs.target?.metadata?.versionName, targetSha256 = inputs.target?.sha256,
                    moduleApkName = inputs.module?.displayName, moduleVersionName = inputs.module?.metadata?.versionName,
                    moduleSha256 = inputs.module?.sha256,
                    phase = if (inputs.target != null && inputs.module != null) PatchPhase.READY else PatchPhase.IDLE)
            }
        } catch (error: Exception) {
            inputs.invalidate(role)
            file.delete()
            throw error
        }
    }

    fun patch() = work {
        inputs.beginPatch()
        val (target, module) = inputs.requireReady()
        mutableState.update { it.copy(phase = PatchPhase.PATCHING, error = null, logLines = emptyList(),
            outputName = null, outputSha256 = null, progressMessage = text(R.string.manager_patching)) }
        NativePatchFiles.ensureLoaded()
        require(Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L) { "page_size" }
        val directory = File(workspace, "output")
        directory.deleteRecursively()
        check(directory.mkdirs())
        val key = LocalPatchKey.loadOrCreate(File(context.noBackupFilesDir, "patch-signing"))
        coroutineContext.ensureActive()
        val patched = PatchEngine().patch(target.file, module.file, directory, key) { stage ->
            // Only known structured stages are exposed; upstream diagnostics contain private paths.
            stageText(stage)?.let { message ->
                if (!cleared.get()) {
                    mutableState.update { it.copy(progressMessage = message, logLines = (it.logLines + message).takeLast(32)) }
                }
            }
        }
        coroutineContext.ensureActive()
        mutableState.update { it.copy(progressMessage = text(R.string.manager_verifying)) }
        val report = verifyResult(NativePatchFiles.verifyPatched(target.file.path, module.file.path, patched.path))
        coroutineContext.ensureActive()
        inputs.recordOutput(patched)
        mutableState.update { it.copy(phase = PatchPhase.SUCCEEDED, progressMessage = null,
            outputName = "${target.metadata.packageName}-nuke-patched.apk", outputSha256 = report.getString("sha256")) }
    }

    fun exportPatched(uri: Uri) = work(exporting = true) {
        val file = checkNotNull(inputs.output)
        check(mutableState.value.phase == PatchPhase.SUCCEEDED)
        try {
            // SAF is Android-owned; copy the verified file without rewriting its ZIP or signature.
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { destination ->
                file.inputStream().use { source ->
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
            messages.trySend(R.string.manager_export_patched_success)
        } catch (error: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw error
        }
    }

    private fun work(exporting: Boolean = false, operation: suspend () -> Unit) {
        if (cleared.get() || worker?.isActive == true) return
        val job = viewModelScope.launch(Dispatchers.IO) {
            if (exporting) {
                mutableState.update { it.copy(isExporting = true, error = null, progressMessage = text(R.string.manager_exporting)) }
            }
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (nativeFailure: UnsatisfiedLinkError) {
                reportFailure(R.string.manager_error_native, exporting)
            } catch (error: Exception) {
                val detail = when {
                    error.message == "page_size" -> R.string.manager_error_page_size
                    error.message == "split_apk" -> R.string.manager_error_split
                    error.message == "self_apk" -> R.string.manager_error_self
                    error.message == "minimum_sdk" -> R.string.manager_error_sdk
                    error.message?.startsWith("module_") == true -> R.string.manager_error_module
                    else -> R.string.manager_error_invalid
                }
                reportFailure(if (exporting) R.string.manager_error_export else detail, exporting)
            } finally {
                if (exporting) mutableState.update { it.copy(isExporting = false, progressMessage = null) }
            }
        }
        worker = job
        job.invokeOnCompletion {
            // Blocking patch/JNI code has returned before its job can complete and release files.
            if (cleared.get()) workspace.deleteRecursively()
        }
    }

    override fun onCleared() {
        cleared.set(true)
        if (worker?.isCompleted != false) workspace.deleteRecursively()
        super.onCleared()
    }

    private fun reportFailure(message: Int, exporting: Boolean) {
        mutableState.update {
            it.copy(phase = if (exporting) it.phase else PatchPhase.FAILED,
                error = text(message), progressMessage = null)
        }
        messages.trySend(R.string.manager_operation_failure)
    }

    private fun stageText(stage: String): String? = when (stage) {
        "stage:READING" -> text(R.string.manager_stage_reading)
        "stage:SIGNING" -> text(R.string.manager_stage_signing)
        "stage:PACKING_SPLIT" -> text(R.string.manager_stage_packing)
        "stage:REWRITING" -> text(R.string.manager_stage_rewriting)
        "stage:INJECTING" -> text(R.string.manager_stage_injecting)
        "stage:EMBEDDING" -> text(R.string.manager_stage_embedding)
        "stage:WRITING" -> text(R.string.manager_stage_writing)
        "stage:DONE", "stage:VERIFYING" -> text(R.string.manager_verifying)
        else -> null
    }

    private fun verifyResult(json: String): JSONObject {
        val value = JSONObject(json)
        check(value.getBoolean("ok")) { value.optJSONObject("error")?.optString("code") ?: "verification" }
        return value
    }

    private fun text(id: Int): String = context.getString(id)
}
