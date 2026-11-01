package io.github.nuke.oss.app.tiktok.settings

import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.Bundle
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.ModuleActivityHook
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.diagnostics.DiagnosticsStore
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.init.SetupStore
import io.github.nuke.oss.app.shared.settings.FeatureActivity
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostSettingsController
import io.github.nuke.oss.app.shared.settings.HostSettingsHandoff
import io.github.nuke.oss.app.shared.script.ScriptRuntime
import io.github.nuke.oss.app.shared.settings.HostSettingsSession
import io.github.nuke.oss.app.shared.settings.ModuleConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.app.tiktok.query.SettingsQueries
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import io.github.nuke.oss.app.shared.environment.EnvironmentCollector

// Adds a NukeApp-OSS row to Douyin's settings screen. The row starts the host settings Activity again as a stub and
// ModuleActivityHook swaps in the module's settings Activity, which then runs inside the host process and reads and
// writes the switches directly. Douyin wraps Instrumentation in subclasses that build Activities without reaching the
// base method, so before a row is added the live instance's overrides are hooked too. The row's label and icon come
// from the module package through the host's PackageManager, so no row is added if the module is not installed, is
// hidden by package visibility, or differs from the code this process loaded (its resource IDs would not match).
// Only the two known settings Activities are hooked: finding a renamed one needs the host's manifest, and no
// Application Context exists yet when features install.
internal class SettingsEntry(
    private val controller: HostSettingsController,
    private val ui: UiConfigStore,
    private val setup: SetupStore,
    private val diagnostics: DiagnosticsStore,
    private val config: ModuleConfig,
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val build: BuildIdentity,
    private val environment: EnvironmentCollector,
    private val logger: Logger,
    private val scripts: ScriptRuntime? = null,
    private val records: FeatureActivity = FeatureActivity { emptyList() },
    private val protocol: ProtocolConsole? = null,
    private val featureFiles: java.io.File? = null,
    private val pets: PetConsole? = null,
) : RuntimeFeature {
    override val id = ID

    private val account = DouyinAccountReader(DouyinAccountReader.service(hostClassLoader)) { error ->
        logger.log("feature=$ID account_unavailable $error")
    }

    override fun install(scope: FeatureScope) {
        val activities = SETTINGS_ACTIVITIES.mapNotNull { name ->
            try { name.toClass(hostClassLoader) } catch (_: ClassNotFoundException) { null }
        }
        check(activities.isNotEmpty()) { "No known Douyin settings Activity: ${SETTINGS_ACTIVITIES.joinToString()}" }
        // Mira would rewire the module Activity's Resources to the host's AssetManager, so module IDs would resolve
        // against the host's table. Without this guard the screen cannot run, so a missing target fails the install.
        val miraResources = resolver.methods(hostClassLoader, SettingsQueries.MIRA_ACTIVITY_RESOURCES).single()
        scope.hooks.install(miraResources, HookSpec(
            id = "KeepModuleResources",
            before = { call -> if (call.argument(0)?.javaClass?.name == HostSettingsHandoff.ACTIVITY) call.returnEarly(null) },
        ))
        val contacts = DouyinContacts.open(resolver, hostClassLoader, logger, ID)
        val layout = SettingsLayout(hostClassLoader)
        val newActivity = Instrumentation::class.resolve().firstMethod {
            name = "newActivity"
            parameters(ClassLoader::class, String::class, Intent::class)
        }.self
        val activityHook = ModuleActivityHook(newActivity, ::createSettingsActivity, logger, ID).also { it.install(scope) }
        for (activity in activities) {
            val fields = layout.fieldsOf(activity)
            val onCreate = activity.resolve().firstMethod { name = "onCreate"; parameters(Bundle::class) }.self
            scope.hooks.install(onCreate, HookSpec(
                id = "InjectEntry:${activity.simpleName}",
                after = { call ->
                    val host = call.receiver as? Activity
                    if (call.outcome is CallOutcome.Returned && host != null && !host.isFinishing && !host.isDestroyed) {
                        inject(host, layout, fields, activityHook, contacts)
                    }
                },
            ))
        }
    }

    // Only a launch that carries this process's session becomes the module Activity; any other start of the stub
    // is the host's own screen.
    private fun createSettingsActivity(launchIntent: Any?): Any? {
        if (HostSettingsHandoff.sessionOf(launchIntent as? Intent) == null) return null
        return HostSettingsHandoff.ACTIVITY.toClass(SettingsEntry::class.java.classLoader, initialize = true).resolve()
            .firstConstructor { emptyParameters() }.create()
    }

    private fun inject(
        activity: Activity,
        layout: SettingsLayout,
        fields: SettingsLayout.Fields,
        activityHook: ModuleActivityHook,
        contacts: HostContacts,
    ) {
        if (layout.isInserted(activity)) return
        val module = moduleContext(activity) ?: return
        // A stub that reuses an existing instance would deliver the launch to the host's screen, not to the module.
        val stub = activity.packageManager.getActivityInfo(activity.componentName, 0)
        if (stub.launchMode != ActivityInfo.LAUNCH_MULTIPLE) {
            logger.log("feature=$ID entry_skipped stub=${stub.name} launchMode=${stub.launchMode}")
            return
        }
        // A row whose launch cannot be intercepted would only reopen the host's own settings.
        val instrumentation = try {
            ModuleActivityHook.currentInstrumentation()
        } catch (error: ReflectiveOperationException) {
            logger.log("feature=$ID entry_skipped instrumentation=$error")
            return
        }
        activityHook.cover(instrumentation)
        val list = layout.findList(activity, fields) ?: error("Douyin settings list not found in ${activity.javaClass.name}")
        // The session outlives this Activity, so it keeps the application context for the report, not the Activity.
        val appContext = activity.applicationContext
        val session = HostSettingsSession(
            activity.packageName, controller, ui, setup, account::read, contacts, diagnostics, config,
            readEnvironment = { version -> environment.collect(appContext, version) },
            scripts = scripts,
            activity = records,
            protocol = protocol,
            featureFiles = featureFiles,
            pets = pets,
        )
        // A debug build is reinstalled under the same version name, so a host process still running the earlier code can
        // find the installed resources renumbered; the row is skipped then rather than failing the host's screen.
        val (label, icon) = try {
            module.getString(R.string.host_settings_label) to requireNotNull(module.resources.getDrawable(R.drawable.ic_settings_entry, module.theme))
        } catch (error: Resources.NotFoundException) {
            logger.log("feature=$ID entry_skipped module_resources_stale ${error.message}")
            return
        }
        val iconPlaced = layout.insertGroup(list, activity, label, icon) {
            open(activity, session)
        }
        logger.log("feature=$ID entry_injected activity=${activity.javaClass.name} icon=$iconPlaced")
    }

    private fun moduleContext(activity: Activity): Context? = when (val opened = ModulePackage.open(activity, build)) {
        is ModulePackage.Opened.Ready -> opened.context
        is ModulePackage.Opened.Unavailable -> {
            logger.log("feature=$ID entry_skipped ${opened.reason}")
            null
        }
    }

    // Runs from a host click listener, outside Hook callback protection, so a failed launch must not escape.
    private fun open(activity: Activity, session: HostSettingsSession) {
        val stub = Intent().setComponent(activity.componentName)
        try {
            activity.startActivity(HostSettingsHandoff.intent(stub, session))
        } catch (error: ActivityNotFoundException) {
            logger.log("feature=$ID open_failed $error")
        } catch (error: SecurityException) {
            logger.log("feature=$ID open_failed $error")
        }
    }

    companion object {
        const val ID = "SettingsEntry"
        private val SETTINGS_ACTIVITIES = listOf(
            "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity",
            "com.ss.android.ugc.aweme.setting.ui.SettingNewVersionActivity",
        )
    }
}
