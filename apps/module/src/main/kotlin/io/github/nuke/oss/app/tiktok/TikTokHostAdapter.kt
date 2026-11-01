package io.github.nuke.oss.app.tiktok

import io.fastkv.FastKV
import io.github.nuke.oss.app.shared.activity.AndroidForeground
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.HostPromptsFactory
import io.github.nuke.oss.app.shared.activity.AndroidSystemBars
import io.github.nuke.oss.app.shared.activity.mainThreadScheduler
import io.github.nuke.oss.app.shared.activity.processExit
import io.github.nuke.oss.app.shared.dex.DexKitSearch
import io.github.nuke.oss.app.shared.diagnostics.DiagnosticsStore
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.diagnostics.FailureRecorder
import io.github.nuke.oss.app.shared.diagnostics.diagnosticsWorker
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexSearch
import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.app.shared.process.MirrorState
import io.github.nuke.oss.app.shared.process.ProcessMirror
import io.github.nuke.oss.app.shared.registry.HostProcess
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.shared.script.AndroidScriptUi
import io.github.nuke.oss.app.shared.script.KeyedScriptStates
import io.github.nuke.oss.app.shared.script.ScriptManifest
import io.github.nuke.oss.app.tiktok.script.DouyinAccountModule
import io.github.nuke.oss.app.tiktok.script.DouyinImModule
import io.github.nuke.oss.app.tiktok.sdk.chat.HostContentSender
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.script.module.FsModule
import io.github.nuke.oss.script.module.HttpModule
import io.github.nuke.oss.script.module.UiModule
import io.github.nuke.oss.app.shared.script.ScriptHold
import io.github.nuke.oss.app.shared.script.ScriptLogs
import io.github.nuke.oss.app.shared.script.ScriptRepository
import io.github.nuke.oss.app.shared.script.ScriptRuntime
import io.github.nuke.oss.app.shared.script.ScriptStateStore
import io.github.nuke.oss.script.SCRIPT_API_VERSION
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.app.shared.dex.FastKvDexCache
import io.github.nuke.oss.app.shared.init.HostInit
import io.github.nuke.oss.app.shared.init.InitPresenter
import io.github.nuke.oss.app.shared.init.SetupStore
import io.github.nuke.oss.app.shared.settings.ConfigKind
import io.github.nuke.oss.app.shared.settings.ConfigValues
import io.github.nuke.oss.app.shared.settings.FeatureActivity
import io.github.nuke.oss.app.shared.settings.FeatureDefault
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.shared.settings.FeatureSwitches
import io.github.nuke.oss.app.shared.settings.HostSettingsController
import io.github.nuke.oss.app.shared.settings.ModuleConfig
import io.github.nuke.oss.app.shared.settings.Option
import io.github.nuke.oss.app.shared.settings.OptionRegistry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.OptionStore
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.UiAppearance
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.app.shared.settings.UiConfigValues
import io.github.nuke.oss.app.shared.settings.ValuesUiConfigStore
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.protocol.PacketHost
import io.github.nuke.oss.app.tiktok.feature.chat.streak.AutoStreak
import io.github.nuke.oss.app.tiktok.feature.comment.CustomCommentStyle
import io.github.nuke.oss.app.tiktok.injector.AndroidAuthorRow
import io.github.nuke.oss.app.tiktok.injector.AndroidBottomInfoLabels
import io.github.nuke.oss.app.tiktok.injector.AndroidChatSwipe
import io.github.nuke.oss.app.shared.activity.AndroidFilePicker
import io.github.nuke.oss.app.shared.media.SilkAudioConverter
import io.github.nuke.oss.app.tiktok.injector.AndroidCommentPanelStyle
import io.github.nuke.oss.app.tiktok.injector.AndroidCommentPages
import io.github.nuke.oss.app.tiktok.injector.AndroidCommentDetailsRow
import io.github.nuke.oss.app.tiktok.injector.AndroidRecallMarks
import io.github.nuke.oss.app.tiktok.injector.AndroidCleanScreen
import io.github.nuke.oss.app.tiktok.injector.AndroidCommentButtons
import io.github.nuke.oss.app.tiktok.injector.AndroidCoverDecorator
import io.github.nuke.oss.app.tiktok.injector.AndroidDanmakuMenu
import io.github.nuke.oss.app.tiktok.injector.AndroidDislikeButton
import io.github.nuke.oss.app.tiktok.injector.AndroidMessageTimeLabels
import io.github.nuke.oss.app.tiktok.injector.AndroidOpacityScreen
import io.github.nuke.oss.app.tiktok.injector.AndroidProgressLabel
import io.github.nuke.oss.app.tiktok.injector.AndroidRepeatMarks
import io.github.nuke.oss.app.tiktok.injector.AndroidFriendPrefixes
import io.github.nuke.oss.app.tiktok.injector.AndroidSharePanelEntries
import io.github.nuke.oss.app.tiktok.injector.AndroidRecallNotice
import io.github.nuke.oss.app.tiktok.injector.AndroidMiniApps
import io.github.nuke.oss.app.tiktok.injector.AndroidPickerViews
import io.github.nuke.oss.app.tiktok.injector.AndroidAvatarPreviews
import io.github.nuke.oss.app.tiktok.injector.AndroidLocationLabels
import io.github.nuke.oss.app.tiktok.injector.AndroidTextCopier
import io.github.nuke.oss.app.tiktok.injector.AndroidMediaFiles
import io.github.nuke.oss.app.tiktok.injector.AndroidNotificationPermission
import io.github.nuke.oss.app.tiktok.injector.AndroidTabMenu
import io.github.nuke.oss.app.tiktok.injector.AndroidModuleStrings
import io.github.nuke.oss.app.tiktok.settings.SettingsEntry
import io.github.nuke.oss.assembly.HostScope
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.HostDescriptor
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import io.github.nuke.oss.app.shared.environment.EnvironmentCollector
import io.github.nuke.oss.app.shared.environment.ProcessFacts

// What the adapter needs from the host's private storage: Dex lookups, per-feature switches, options and memory, the
// screen appearance, the first-launch choices and the diagnostics. The resolver's catalog is every Dex query the init analyses.
internal class TikTokServices(
    val resolver: DexResolver,
    val switches: FeatureSwitches,
    val ui: UiConfigStore,
    val options: OptionStore,
    val memory: FeatureMemory,
    val setup: SetupStore,
    val diagnostics: DiagnosticsStore,
    val config: ModuleConfig,
    val streakDirectory: File,
    val mirror: ProcessMirror,
    val scope: String,
    val scriptRoot: File,
    val scriptStates: ScriptStateStore,
)

// Hooks only the Douyin main process. Features install lazily through FeatureManager, so a failed Dex lookup
// leaves that feature FAILED without blocking the others.
class TikTokHostAdapter internal constructor(
    private val services: (HostPaths, String) -> TikTokServices,
    private val presenter: (BuildIdentity, Logger) -> InitPresenter,
    private val prompts: HostPromptsFactory,
    private val diagnosticsWorker: Executor = diagnosticsWorker(),
) : HostAdapter {
    internal constructor(presenter: (BuildIdentity, Logger) -> InitPresenter, prompts: HostPromptsFactory) : this(::hostServices, presenter, prompts)

    override val descriptor = HostDescriptor("tiktok", HostScope.TIKTOK, implemented = true)

    override fun register(environment: HostEnvironment, manager: FeatureManager) {
        val paths = checkNotNull(environment.event.hostPaths) { "The TikTok host needs the host data and APK paths" }
        val process = HostScope.processOf(environment.event.process.packageName, environment.event.process.processName)
        if (process != HostProcess.MAIN) return registerChild(environment, manager, paths, checkNotNull(process) { "The process is outside the host scope" })
        val host = services(paths, environment.event.build.buildId)
        (host.switches as? MirroredSwitches)?.onFailure = { environment.logger.log("process_mirror_failed ${it.javaClass.simpleName}") }
        val loader = environment.event.hostClassLoader
        // Failures are recorded from here on. A feature with a record stays off until it is unlocked on the debug
        // page, and safe mode keeps every feature off; both still register, so the settings list and status show them.
        val recorder = FailureRecorder(
            host.diagnostics, setOf(SettingsEntry.ID, INIT_ID), { id -> manager.setEnabled(id, false) }, environment.logger, diagnosticsWorker,
        )
        manager.failureSink = recorder
        val locked = recorder.locked()
        val safeMode = host.diagnostics.safeMode()
        if (safeMode || locked.isNotEmpty()) environment.logger.log("features_held safe_mode=$safeMode locked=${locked.size}")
        // Options are read here once, like the switches; the registry remembers what each feature was built with.
        val optionRegistry = OptionRegistry(host.options)
        // Every @HookEntry of this package, from the generated registry. Only the first migrated feature defaults to
        // on; the rest, new and unverified on a real
        // host, start off as well.
        val services = featureServices(
            environment, loader, host.resolver, optionRegistry::values, host.memory, host.streakDirectory,
            { id -> prompts.create(id, environment.event.build, environment.logger) { UiAppearance(host.ui).read() } },
        )
        val features = generatedHookEntries.filter { HostProcess.MAIN in it.processes }
            .flatMap { entry -> entry.create(services).map { Built(it, entry.enabledByDefault, entry.processes) } }
        val pending = host.resolver.pending()
        if (pending.isNotEmpty()) {
            // A new host or module build: like NukeCore, install nothing this launch and let the init ask its choices,
            // analyse every query and restart the host. The features are registered so their state is still reported.
            for ((feature, _) in features) manager.register(feature)
            val init = HostInit(
                INIT_ID, host.ui, host.setup,
                analyze = { progress -> host.resolver.analyze(progress) },
                presenter = presenter(environment.event.build, environment.logger),
                logger = environment.logger,
            )
            manager.register(init)
            environment.logger.log("feature=$INIT_ID init_pending queries=${pending.size}")
            manager.setEnabled(INIT_ID, true)
            return
        }
        val held = locked + holdUnlocated(features, host.resolver, recorder, locked, environment.logger)
        for ((feature, default) in features) {
            manager.register(feature)
            if (safeMode || feature.id in held) continue
            // Switches are read once here; hook callbacks never touch storage.
            if (host.switches.read(feature.id, default)) manager.setEnabled(feature.id, true)
        }
        writeMirror(environment, host, features, safeMode, held)
        val scripts = registerScripts(environment, manager, host, loader, paths, safeMode, locked)
        val collector = EnvironmentCollector(
            environment.event.loaderId, environment.event.framework,
            ProcessFacts(environment.event.process.processName, environment.event.process.pid),
            environment.event.build,
        ) { host.resolver.status() }
        // The entry is how switches get changed, so it is always on and not itself switchable.
        val controller = HostSettingsController(
            features.map { (feature, default) -> FeatureDefault(feature.id, default) }, host.switches,
            optionRegistry.features(), host.options, manager::snapshot,
        )
        // The streak journal is the only activity record a feature keeps so far.
        val streakAccount = CustomCommentStyle.accountUserId(loader)
        val activity = FeatureActivity { id -> if (id == AutoStreak.ID) AutoStreak.history(host.streakDirectory, streakAccount()) else emptyList() }
        val protocol = features.map { it.feature }.filterIsInstance<ProtocolConsole>().singleOrNull()
        val pets = features.map { it.feature }.filterIsInstance<PetConsole>().singleOrNull()
        manager.register(SettingsEntry(
            controller, host.ui, host.setup, host.diagnostics, host.config, host.resolver, loader, environment.event.build, collector,
            environment.logger, scripts, activity, protocol, File(paths.dataDir, FEATURE_FILES), pets,
        ))
        manager.setEnabled(SettingsEntry.ID, true)
    }

    // Installed scripts run in the main process as features of their own, held by safe mode and failure records like
    // the rest. A broken script store only costs the scripts, never the host's features.
    private fun registerScripts(
        environment: HostEnvironment, manager: FeatureManager, host: TikTokServices, loader: ClassLoader,
        paths: HostPaths, safeMode: Boolean, locked: Set<String>,
    ): ScriptRuntime? {
        return try {
            val repository = ScriptRepository(host.scriptRoot, HostScope.TIKTOK, SCRIPT_API_VERSION)
            // Resolves its host targets on the first send, so a script that never sends needs none of them.
            val sender = HostContentSender(host.resolver, loader)
            ScriptRuntime(
                repository, host.scriptStates, ScriptLogs(), manager, loader, ScriptEngine(), diagnosticsWorker,
                hold = { id ->
                    when {
                        safeMode -> ScriptHold.SAFE_MODE
                        id in locked -> ScriptHold.LOCKED
                        else -> ScriptHold.NONE
                    }
                },
                // ScriptApiContractTest builds the same list to check it against the SDK's types; keep them in step.
                hostModules = { definition, scope ->
                    val featureId = ScriptManifest.featureIdOf(definition.id)
                    val activities = ResumedActivity().also { it.install(scope, featureId) }
                    listOf(
                        HttpModule(),
                        FsModule(repository.dataDir(definition.id), File(paths.dataDir)),
                        UiModule(AndroidScriptUi(activities)),
                        DouyinAccountModule(DouyinAccountReader(DouyinAccountReader.service(loader)) { error ->
                            environment.logger.log("feature=$featureId account_unavailable ${error.javaClass.simpleName}")
                        }),
                        DouyinImModule(sender, sender::text),
                    )
                },
            ).also { it.registerAll() }
        } catch (error: IOException) {
            environment.logger.log("scripts_unavailable ${error.javaClass.simpleName}: ${error.message}")
            null
        } catch (error: RuntimeException) {
            environment.logger.log("scripts_unavailable ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private data class Built(val feature: RuntimeFeature, val default: Boolean, val processes: Set<HostProcess>)

    // A feature that cannot work without a query the analysis did not locate is recorded as a Dex failure and held off
    // now, switched on or not, so the settings list shows it failed before anyone turns it on. Unlocking it on the debug
    // page holds it again on the next launch for as long as this host build lacks the query.
    private fun holdUnlocated(
        features: List<Built>, resolver: DexResolver, recorder: FailureRecorder, locked: Set<String>, logger: Logger,
    ): Set<String> {
        val unlocated = resolver.failures()
        if (unlocated.isEmpty()) return emptySet()
        val held = LinkedHashSet<String>()
        for ((feature) in features) {
            val queries = (feature as? UsesDexQueries)?.dexQueries ?: continue
            if (feature.id in locked || !recorder.lockable(feature.id)) continue
            val missing = queries.map(resolver::nameOf).filter { it in unlocated }
            if (missing.isEmpty() || !feature.unusable(queries.filter { resolver.nameOf(it) in unlocated })) continue
            val reasons = missing.joinToString("; ") { "Dex query $it was not located during analysis: ${unlocated.getValue(it)}" }
            recorder.report(feature.id, FailurePhase.INSTALL, DexQueryUnavailable(reasons))
            held += feature.id
        }
        if (held.isNotEmpty()) logger.log("features_held dex_unlocated=${held.size}")
        return held
    }

    // What the child processes read: the switches of the features that run in them, the held-off features, safe mode and
    // the located Dex descriptors. Only after the Dex analysis, so a child never sees a half-analysed build.
    private fun writeMirror(environment: HostEnvironment, host: TikTokServices, features: List<Built>, safeMode: Boolean, locked: Set<String>) {
        val shared = features.filter { it.processes != HostProcess.MAIN_ONLY }
        if (shared.isEmpty()) return
        val state = MirrorState(
            host.scope, shared.associate { it.feature.id to host.switches.read(it.feature.id, it.default) }, safeMode, locked,
            host.resolver.cachedEntries(),
        )
        try {
            host.mirror.write(state)
        } catch (error: java.io.IOException) {
            environment.logger.log("process_mirror_failed ${error.javaClass.simpleName}")
        }
    }

    // A child process of the host: no settings screen, no init and no store of its own. It installs the features that
    // are declared for its kind of process and switched on, from the main process's mirror, and searches no Dex.
    private fun registerChild(environment: HostEnvironment, manager: FeatureManager, paths: HostPaths, process: HostProcess) {
        val logger = environment.logger
        val directory = File(paths.dataDir, "no_backup/nuke")
        val scope = buildScope(paths, environment.event.build.buildId)
        val state = ProcessMirror(File(directory, MIRROR_FILE)).read(scope)
        if (state == null) return logger.log("child_skipped process=${environment.event.process.processName} no_mirror")
        if (state.safeMode) return logger.log("child_skipped process=${environment.event.process.processName} safe_mode")
        manager.failureSink = FeatureFailureSink { id, phase, error -> logger.log("feature=$id child_failure phase=$phase ${error.javaClass.simpleName}") }
        val resolver = DexResolver(
            object : DexCache {
                override fun get(key: String) = state.dex[key]
                override fun put(key: String, descriptor: String) = Unit
            },
            scope,
            // A query the main process did not locate stays unavailable here; a child never scans the Dex files.
            DexSearch { queries, _, onEach ->
                queries.keys.forEach { onEach(it, Result.failure(DexQueryUnavailable("Dex query $it was not analysed by the main process"))) }
            },
            generatedDexQueries,
        )
        val readOnlyOptions = OptionRegistry(object : OptionStore {
            override fun read(featureId: String, key: String): String? = null
            override fun write(featureId: String, key: String, value: String) = Unit
        })
        val services = featureServices(
            environment, environment.event.hostClassLoader, resolver, readOnlyOptions::values,
            object : FeatureMemory {
                override fun read(featureId: String, key: String): String? = null
                override fun write(featureId: String, key: String, value: String) = Unit
            },
            File(directory, "streak"),
            // A child process has no appearance store of its own; its dialogs take the default appearance.
            { id -> prompts.create(id, environment.event.build, environment.logger) { UiConfig() } },
            process,
        )
        for (entry in generatedHookEntries.filter { process in it.processes }) {
            for (feature in entry.create(services)) {
                manager.register(feature)
                if (state.switches[feature.id] == true && feature.id !in state.locked) manager.setEnabled(feature.id, true)
            }
        }
        logger.log("child_ready process=${environment.event.process.processName} kind=$process")
    }

    internal companion object {
        const val INIT_ID = "Init"
        const val MIRROR_FILE = "process-mirror.json"
        const val FEATURE_FILES = "no_backup/nuke/feature-files"
    }
}

// The switches the settings screen writes, with the shared state for child processes kept in step with them.
internal class MirroredSwitches(private val inner: FeatureSwitches, private val mirror: ProcessMirror, private val scope: String) : FeatureSwitches {
    var onFailure: (java.io.IOException) -> Unit = {}
    override fun read(featureId: String, default: Boolean) = inner.read(featureId, default)
    override fun write(featureId: String, enabled: Boolean) {
        inner.write(featureId, enabled)
        try { mirror.updateSwitch(scope, featureId, enabled) } catch (error: java.io.IOException) { onFailure(error) }
    }
}

private fun buildScope(paths: HostPaths, buildId: String): String {
    val apk = File(paths.apkPath)
    return "$buildId|${apk.length()}|${apk.lastModified()}"
}

// What every feature of this host is built from. The UI pieces are the Android ones; a child process builds the same
// services, so a feature declared for it behaves as in the main process.
private fun featureServices(
    environment: HostEnvironment,
    loader: ClassLoader,
    resolver: DexResolver,
    options: (String, List<Option>) -> OptionValues,
    memory: FeatureMemory,
    streakDirectory: File,
    prompts: (featureId: String) -> HostPrompts,
    process: HostProcess = HostProcess.MAIN,
): TikTokFeatureServices {
        val moduleStrings = AndroidModuleStrings(environment.event.build, environment.logger)
        return TikTokFeatureServices(
            resolver, loader, options, environment.logger,
            prompts = prompts,
            systemBars = ::AndroidSystemBars, foreground = ::AndroidForeground, exit = processExit, scheduler = mainThreadScheduler, memory = memory,
            covers = { AndroidCoverDecorator(environment.event.build, environment.logger) },
            cleanScreen = ::AndroidCleanScreen,
            opacityScreen = ::AndroidOpacityScreen,
            messageLabels = ::AndroidMessageTimeLabels,
            chatSwipe = ::AndroidChatSwipe,
            repeatMarks = { AndroidRepeatMarks(moduleStrings) },
            sharePanelEntries = { AndroidSharePanelEntries(resolver, loader, File(checkNotNull(environment.event.hostPaths) { "The host paths are unknown" }.dataDir, "code_cache/nuke_share_panel"), moduleStrings, environment.logger) },
            friendPrefixes = { AndroidFriendPrefixes(loader, File(checkNotNull(environment.event.hostPaths) { "The host paths are unknown" }.dataDir, "code_cache/nuke_share_groups"), moduleStrings, environment.logger) },
            recallNoticeUi = { AndroidRecallNotice(moduleStrings) },
            recallMarks = ::AndroidRecallMarks,
            rewardAdUi = ::AndroidMiniApps,
            realNameDialogs = ::AndroidMiniApps,
            pickerViews = ::AndroidPickerViews,
            avatarPreviews = ::AndroidAvatarPreviews,
            locationLabels = ::AndroidLocationLabels,
            textCopier = ::AndroidTextCopier,
            mediaFiles = { AndroidMediaFiles(loader) },
            notificationPermission = ::AndroidNotificationPermission,
            commentButtons = ::AndroidCommentButtons,
            commentPanelStyle = ::AndroidCommentPanelStyle,
            commentPages = { AndroidCommentPages(environment.logger) },
            commentDetailsRow = { AndroidCommentDetailsRow(environment.event.build, environment.logger) },
            filePicker = { name -> AndroidFilePicker(File(checkNotNull(environment.event.hostPaths) { "The host paths are unknown" }.dataDir, "no_backup/nuke/picked/$name"), environment.logger) },
            audioConverter = ::SilkAudioConverter,
            danmakuMenu = { AndroidDanmakuMenu(environment.event.build, environment.logger) },
            authorRow = { AndroidAuthorRow(environment.event.build, environment.logger) },
            moduleStrings = moduleStrings,
            tabMenu = { AndroidTabMenu(moduleStrings, environment.logger) },
            dislikeButton = { AndroidDislikeButton(environment.event.build, environment.logger) },
            progressLabel = { AndroidProgressLabel() },
            bottomInfoLabels = { AndroidBottomInfoLabels() },
            streakDirectory = streakDirectory,
            packetTransport = { PacketHost(resolver, loader, File(checkNotNull(environment.event.hostPaths) { "The host paths are unknown" }.dataDir, "code_cache/nuke_packet")) },
            featureFiles = File(checkNotNull(environment.event.hostPaths) { "The host paths are unknown" }.dataDir, TikTokHostAdapter.FEATURE_FILES),
            process = process,
        )
}

private fun hostServices(paths: HostPaths, buildId: String): TikTokServices {
    val directory = File(paths.dataDir, "no_backup/nuke")
    val scope = buildScope(paths, buildId)
    val mirror = ProcessMirror(File(directory, TikTokHostAdapter.MIRROR_FILE))
    val dexCache = FastKvDexCache(directory, scope)
    val resolver = DexResolver(dexCache, scope, DexKitSearch(paths.apkPath), generatedDexQueries)
    val store = FastKV.Builder(directory.path, "feature-state").build()
    val background = File(directory, "home-background")
    // An absent switch takes the feature's default; the settings entry writes explicit values.
    val switches = MirroredSwitches(object : FeatureSwitches {
        override fun read(featureId: String, default: Boolean) = store.getBoolean("enabled.$featureId", default)
        override fun write(featureId: String, enabled: Boolean) { store.putBoolean("enabled.$featureId", enabled) }
    }, mirror, scope)
    val ui = ValuesUiConfigStore(object : UiConfigValues {
        override fun getInt(key: String, default: Int) = store.getInt(key, default)
        override fun getLong(key: String, default: Long) = store.getLong(key, default)
        override fun getFloat(key: String, default: Float) = store.getFloat(key, default)
        override fun getBoolean(key: String, default: Boolean) = store.getBoolean(key, default)
        override fun getString(key: String): String? = store.getString(key, "")?.takeIf { it.isNotEmpty() }
        override fun put(key: String, value: Any?) {
            when (value) {
                null -> store.remove(key)
                is Int -> store.putInt(key, value)
                is Long -> store.putLong(key, value)
                is Float -> store.putFloat(key, value)
                is Boolean -> store.putBoolean(key, value)
                is String -> store.putString(key, value)
                else -> error("Unsupported value type ${value.javaClass.name}")
            }
        }
    }, background)
    // Options share the switch store; a stored empty string is a real value (an emptied list), so absence is checked.
    val options = object : OptionStore {
        override fun read(featureId: String, key: String): String? =
            "option.$featureId.$key".let { name -> if (store.contains(name)) store.getString(name, "") else null }
        override fun write(featureId: String, key: String, value: String) { store.putString("option.$featureId.$key", value) }
    }
    val memory = object : FeatureMemory {
        override fun read(featureId: String, key: String): String? =
            "memory.$featureId.$key".let { name -> if (store.contains(name)) store.getString(name, "") else null }
        override fun write(featureId: String, key: String, value: String) { store.putString("memory.$featureId.$key", value) }
    }
    // Absent until the init has asked; the host settings change it afterwards.
    val setup = object : SetupStore {
        override fun bugReports(): Boolean? =
            if (store.contains(BUG_REPORTS_KEY)) store.getBoolean(BUG_REPORTS_KEY, false) else null
        override fun setBugReports(enabled: Boolean) { store.putBoolean(BUG_REPORTS_KEY, enabled) }
    }
    // Each record is one value under failure.<feature id>, so the debug page lists them without an index.
    val diagnostics = object : DiagnosticsStore {
        override fun read(): List<FailureRecord> = store.all.mapNotNull { (key, value) ->
            val id = key.removePrefix(FAILURE_PREFIX).takeIf { key.startsWith(FAILURE_PREFIX) } ?: return@mapNotNull null
            (value as? String)?.let { FailureRecord.decode(id, it) }
        }.sortedByDescending { it.timestamp }
        override fun write(record: FailureRecord) { store.putString(FAILURE_PREFIX + record.featureId, record.encode()) }
        override fun clear(featureId: String) { store.remove(FAILURE_PREFIX + featureId) }
        override fun safeMode() = store.getBoolean(SAFE_MODE_KEY, false)
        override fun setSafeMode(enabled: Boolean) { store.putBoolean(SAFE_MODE_KEY, enabled) }
    }
    // Only feature-state is handed over as values: the DexKit cache is a store of its own and never reaches a config file,
    // but cleaning forgets it, and removes the mirror the child processes read, so the next start analyses again.
    val config = ModuleConfig(HostScope.TIKTOK, object : ConfigValues {
        override fun all(): Map<String, Any?> = store.all
        override fun put(key: String, value: Any) {
            when (value) {
                is Boolean -> store.putBoolean(key, value)
                is Int -> store.putInt(key, value)
                is Long -> store.putLong(key, value)
                is Float -> store.putFloat(key, value)
                is String -> store.putString(key, value)
                else -> error("Unsupported value type ${value.javaClass.name}")
            }
        }
        override fun remove(key: String) { store.remove(key) }
        override fun clear() { store.clear() }
    }, ::preferenceKind, forgetDex = dexCache::clear) {
        background.delete()
        mirror.delete()
    }
    // One JSON value per script beside the switches; not a preference, so a config export leaves scripts out.
    val scriptStates = KeyedScriptStates(
        get = { key -> store.getString(key, "")?.takeIf { it.isNotEmpty() } },
        put = { key, value -> if (value == null) store.remove(key) else store.putString(key, value) },
    )
    return TikTokServices(
        resolver, switches, ui, options, memory, setup, diagnostics, config, File(directory, "streak"), mirror, scope,
        File(directory, "scripts"), scriptStates,
    )
}

// The keys of feature-state that hold the user's choices, with their stored types. Failure records, feature memory,
// safe mode and the background revision, which names a local image file, are not preferences.
internal fun preferenceKind(key: String): ConfigKind? = when {
    key.startsWith("enabled.") -> ConfigKind.BOOLEAN
    key.startsWith("option.") -> ConfigKind.STRING
    key == BUG_REPORTS_KEY -> ConfigKind.BOOLEAN
    else -> UI_PREFERENCES[key]
}

// Keep in step with ValuesUiConfigStore.
private val UI_PREFERENCES = mapOf(
    "ui.theme" to ConfigKind.INT,
    "ui.language" to ConfigKind.INT,
    "ui.accent" to ConfigKind.STRING,
    "ui.recent_accents" to ConfigKind.STRING,
    "ui.click_haptic" to ConfigKind.BOOLEAN,
    "ui.background_blur" to ConfigKind.FLOAT,
    "ui.background_scrim" to ConfigKind.FLOAT,
    "ui.card_opacity" to ConfigKind.FLOAT,
    "ui.card_blur" to ConfigKind.BOOLEAN,
)

private const val BUG_REPORTS_KEY = "setup.bug_reports"
private const val SAFE_MODE_KEY = "setup.safe_mode"
private const val FAILURE_PREFIX = "failure."
