package io.github.nuke.oss.status

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

// What the module's own process learns about the frameworks that load it. An XP82 framework loads the module into
// this process too, and the XP82 entry reports itself here; an API 102 framework instead hands over its service
// binder through the provider the service library declares. A root (Zygisk) or patched install leaves no trace here.
object ModuleStatus {
    @Volatile private var legacy: String? = null
    private val bound = MutableStateFlow<XposedService?>(null)
    private val listening = AtomicBoolean()

    val legacyFramework: String? get() = legacy
    val service: StateFlow<XposedService?> = bound.asStateFlow()

    // Called by name from the XP82 entry in this process; keep in step with LoaderProfiles and proguard-rules.pro.
    @JvmStatic fun reportLegacyFramework(framework: String) {
        legacy = framework
    }

    // The helper caches a binder that arrives before anyone listens, so registering on first use loses nothing.
    fun listen() {
        if (!listening.compareAndSet(false, true)) return
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                bound.value = service
            }

            override fun onServiceDied(service: XposedService) {
                bound.compareAndSet(service, null)
            }
        })
    }
}
