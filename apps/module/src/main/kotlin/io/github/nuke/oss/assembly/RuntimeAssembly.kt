package io.github.nuke.oss.assembly

import io.github.nuke.oss.app.fixture.FixtureHostAdapter
import io.github.nuke.oss.app.shared.activity.HostPromptsFactory
import io.github.nuke.oss.app.shared.integrity.ModuleSignature
import io.github.nuke.oss.app.tiktok.TikTokHostAdapter
import io.github.nuke.oss.common.closeAll
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.core.RuntimeSnapshot
import io.github.nuke.oss.hook.api.HookBackend
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.runtime.*
import io.github.nuke.oss.ui.init.InitDialogPresenter
import io.github.nuke.oss.ui.prompts.ComposePrompts
import java.util.concurrent.atomic.AtomicBoolean

// The TikTok init dialog and feature prompts are wired here, so host code never links UI classes. signatureRefusal says
// why the module may not run in this process (its APK is not signed with the release certificate), or null when it may.
class RuntimeAssembly(
    private val adapters: List<HostAdapter> = listOf(FixtureHostAdapter(), TikTokHostAdapter(::InitDialogPresenter, HostPromptsFactory(::ComposePrompts))),
    private val signatureRefusal: (LoadEvent, Logger) -> String? = ::moduleSignatureRefusal,
) : RuntimeBootstrap {
    private val lock = Any()
    private var session: Session? = null

    override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult = synchronized(lock) {
        session?.let {
            if (it.closed.get()) return BootstrapResult.Ignored("Runtime closed; restart the host process")
            check(it.event == event && it.backend === services.backend) { "A different runtime is already active" }
            return BootstrapResult.Started(it)
        }
        val adapter = adapters.singleOrNull { it.descriptor.implemented && it.descriptor.packageName == event.process.packageName }
            ?: return BootstrapResult.Ignored("No implemented host adapter")
        if (!HostScope.isTargetProcess(event.process.packageName, event.process.processName)) {
            return BootstrapResult.Ignored("The process is outside the host scope")
        }
        signatureRefusal(event, services.logger)?.let { reason ->
            services.logger.log("module_signature_refused $reason")
            return BootstrapResult.Ignored(reason)
        }
        // The manager is built after the Hook service it needs, so callback failures reach it through this holder.
        var failures: FeatureManager? = null
        val hooks = DefaultHookService(services.backend, HookFailureSink {
            services.logger.log("hook_failure owner=${it.owner.featureId} id=${it.subscriptionId} phase=${it.phase} error=${it.error}")
            val phase = if (it.phase == "before") FailurePhase.BEFORE_CALLBACK else FailurePhase.AFTER_CALLBACK
            failures?.reportFailure(it.owner.featureId, phase, it.error)
        })
        val manager = FeatureManager(event.build.generationId, hooks, services.logger).also { failures = it }
        try {
            adapter.register(HostEnvironment(event, services.backend.id, services.logger), manager)
            val created = Session(event, adapter.descriptor.id, services.backend, manager, hooks)
            session = created
            services.logger.log("runtime_ready host=${adapter.descriptor.id} backend=${services.backend.id}")
            BootstrapResult.Started(created)
        } catch (error: Throwable) {
            try { listOf(hooks, manager).asReversed().closeAll() } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private class Session(
        val event: LoadEvent,
        private val hostId: String,
        val backend: HookBackend,
        private val manager: FeatureManager,
        private val hooks: DefaultHookService,
    ) : RuntimeSessionView {
        val closed = AtomicBoolean()
        override fun snapshot() = RuntimeSnapshot(event.build, event.process, hostId, backend.id, manager.snapshot())
        override fun close() {
            if (closed.compareAndSet(false, true)) listOf<AutoCloseable>(hooks, manager).asReversed().closeAll()
        }
    }
}

// The module APK is the one the entry names (Zygisk builds its loader from memory) or the one this class was loaded from.
internal fun moduleSignatureRefusal(event: LoadEvent, logger: Logger): String? {
    val apk = event.moduleApkPath ?: ModuleSignature.apkOf(RuntimeAssembly::class.java.classLoader)
        ?: return null.also { logger.log("module_signature unchecked no module APK") }
    return ModuleSignature.refusal(apk, logger::log)
}
