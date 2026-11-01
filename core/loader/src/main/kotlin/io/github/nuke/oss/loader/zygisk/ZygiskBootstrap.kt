package io.github.nuke.oss.loader.zygisk

import io.github.nuke.oss.common.ProcessEntryGate
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.*
import io.github.nuke.oss.loader.LoaderProfile
import io.github.nuke.oss.runtime.*
import java.lang.reflect.Method

// The process-lifetime startup binding remains resident while any callback can run.
class ZygiskBootstrap(
    private val profile: LoaderProfile,
    private val process: ProcessIdentity,
    private val build: BuildIdentity,
    private val backend: HookBackend,
    private val initialize: () -> Boolean,
    private val bootstrap: RuntimeBootstrap,
    private val logger: Logger,
    private val contextLoader: (Any) -> ClassLoader,
    private val contextPaths: (Any) -> HostPaths?,
    private val gate: ProcessEntryGate = ProcessEntryGate(),
    // Runs with the host Context before the runtime starts; it lets the payload loader find the module's native
    // libraries and returns the installed module APK of this build for the signature check, or null when there is none.
    // A failure is logged and the runtime still starts, so features that need no native library keep working.
    private val prepareNatives: (Any) -> String? = { null },
    // Runs once this entry owns the process, before LSPlant starts: lifts the hidden-API restriction for the framework
    // members the module uses. A failure is logged and startup goes on; those members then fail where they are used.
    private val exemptHiddenApis: () -> Unit = {},
) {
    private var started = false
    private var attempted = false
    private var delivered = false
    private var attachBinding: BackendBinding? = null

    @Synchronized fun start(applicationCreation: Method): Boolean {
        if (started) return true
        if (!profile.isTargetProcess(process.packageName, process.processName) || process.uid % 100000 !in 10000..19999) return false
        if (!gate.claim("zygisk")) {
            logger.log("zygisk_skipped entry already claimed")
            return false
        }
        check(!attempted) { "Zygisk startup already failed; restart the host process" }
        attempted = true
        try {
            exemptHiddenApis()
        } catch (error: Exception) {
            logger.log("zygisk_hidden_api_unavailable ${error.javaClass.name}: ${error.message}")
        }
        // Never release the gate after initialization begins: partial ART changes cannot be rolled back safely.
        check(initialize()) { "LSPlant initialization failed; restart the host process" }
        attachBinding = backend.attach(applicationCreation, object : BackendCallbacks {
            override fun enter(frame: BackendFrame): InvocationToken = AttachToken
            override fun exit(frame: BackendFrame, token: InvocationToken) {
                if ((frame.state as? CallState.Completed)?.outcome is CallOutcome.Returned) {
                    try {
                        val context = requireNotNull(frame.arguments.last())
                        val moduleApk = try {
                            prepareNatives(context)
                        } catch (error: Exception) {
                            logger.log("zygisk_natives_unavailable ${error.javaClass.name}: ${error.message}")
                            null
                        }
                        hostReady(contextLoader(context), contextPaths(context), moduleApk)
                    } catch (error: Throwable) {
                        logger.log("zygisk_runtime_failed ${error.javaClass.name}: ${error.message}")
                    }
                }
            }
        })
        started = true
        logger.log("zygisk_waiting Instrumentation.newApplication")
        return true
    }

    @Synchronized private fun hostReady(loader: ClassLoader, paths: HostPaths?, moduleApk: String?) {
        if (delivered) return
        // A failed bootstrap is sticky too; do not create another partially installed runtime on reentry.
        delivered = true
        val result = bootstrap.accept(
            LoadEvent(process, build, "zygisk", LoadStage.HOST_CLASSLOADER_READY, loader, paths, moduleApk, FrameworkInfo("Zygisk")),
            BootstrapServices(backend, logger),
        )
        logger.log(if (result is BootstrapResult.Started) "zygisk_runtime_ready" else "zygisk_runtime_ignored")
    }

    private object AttachToken : InvocationToken
}
