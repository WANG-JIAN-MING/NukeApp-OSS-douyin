package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.diagnostics.MemoryDiagnosticsStore
import io.github.nuke.oss.app.shared.init.SetupStore
import io.github.nuke.oss.app.shared.settings.ConfigKind
import io.github.nuke.oss.app.shared.settings.ConversationsOption
import io.github.nuke.oss.app.shared.settings.FeatureDefault
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.FeatureOptions
import io.github.nuke.oss.app.shared.settings.FeatureSwitches
import io.github.nuke.oss.app.shared.settings.MemoryConfigValues
import io.github.nuke.oss.app.shared.settings.MemoryOptionStore
import io.github.nuke.oss.app.shared.settings.ModuleConfig
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.load
import io.github.nuke.oss.app.shared.settings.HostSettingsController
import io.github.nuke.oss.app.shared.settings.HostSettingsSession
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.UiConfigStore
import io.github.nuke.oss.app.shared.settings.ThemeChoice
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import io.github.nuke.oss.app.shared.environment.EnvironmentInfo
import io.github.nuke.oss.app.shared.environment.ModuleVersion
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.OptionEntry

class HostSettingsViewModelTest {
    private val stored = LinkedHashMap<String, Boolean>()
    private val controller = HostSettingsController(
        listOf(FeatureDefault("a", false)),
        object : FeatureSwitches {
            override fun read(featureId: String, default: Boolean) = stored[featureId] ?: default
            override fun write(featureId: String, enabled: Boolean) { stored[featureId] = enabled }
        },
    ) { listOf(FeatureStatus("a", FeatureState.DISABLED)) }
    private var storedUi = UiConfig()
    private val ui = object : UiConfigStore {
        override val backgroundFile = java.io.File("unused-background")
        override fun read() = storedUi
        override fun write(config: UiConfig) { storedUi = config }
    }
    private var bugReports: Boolean? = null
    private val setup = object : SetupStore {
        override fun bugReports() = bugReports
        override fun setBugReports(enabled: Boolean) { bugReports = enabled }
    }
    private val diagnostics = MemoryDiagnosticsStore()
    private val noContacts = HostContacts { throw HostContactsUnavailable("none") }
    private val noEnvironment: (ModuleVersion) -> EnvironmentInfo = { EnvironmentInfo(emptyList()) }
    private val configValues = MemoryConfigValues()
    private val config = ModuleConfig("com.example.host", configValues, { key -> ConfigKind.BOOLEAN.takeIf { key.startsWith("enabled.") } }, forgetDex = {}) {}
    private var account: HostAccount = HostAccount.SignedIn("1", "first", listOf("https://cdn/first"))
    private val model = HostSettingsViewModel(HostSettingsSession("com.example.host", controller, ui, setup, { account }, noContacts, diagnostics, config, noEnvironment))

    @Test fun `the account is read at start and refreshed on return`() {
        assertEquals(account, model.state.value.account)
        account = HostAccount.Unavailable
        model.refresh()
        assertEquals(HostAccount.SignedIn("1", "first", listOf("https://cdn/first")), model.state.value.account)
        account = HostAccount.SignedOut
        model.refresh()
        assertEquals(HostAccount.SignedOut, model.state.value.account)
    }

    @Test fun `the screen starts live with the stored switches`() {
        val state = model.state.value
        assertEquals(HostSession.LIVE, state.session)
        assertEquals("com.example.host", state.host)
        assertEquals(false, state.features.single().configured)
    }

    @Test fun `the bug report choice from the init is shown and can be changed`() {
        assertEquals(null, model.state.value.bugReports)
        model.setBugReports(true)
        assertEquals(true, bugReports)
        assertEquals(true, model.state.value.bugReports)
        model.setBugReports(false)
        assertEquals(false, bugReports)
    }

    @Test fun `leaving asks for a restart only for changes made on this visit`() {
        assertFalse(model.state.value.restartNeeded)
        model.setEnabled("a", true)
        assertTrue(model.state.value.restartNeeded)
        // Switching it back leaves nothing for a restart to apply.
        model.setEnabled("a", false)
        assertFalse(model.state.value.restartNeeded)
    }

    @Test fun `a change left over from an earlier visit does not ask again until something else changes`() {
        stored["a"] = true
        val reopened = HostSettingsViewModel(HostSettingsSession("com.example.host", controller, ui, setup, { account }, noContacts, diagnostics, config, noEnvironment))
        assertFalse(reopened.state.value.restartNeeded)
        reopened.setEnabled("a", false)
        assertFalse(reopened.state.value.restartNeeded)
        reopened.setEnabled("a", true)
        assertFalse(reopened.state.value.restartNeeded)
    }

    @Test fun `an option saved with a value other than the running one is waiting for a restart`() {
        val minutes = NumberOption("minutes", default = 10, min = 1, max = 120)
        val waiting = HostFeature("a", true, FeatureState.ACTIVE, options = listOf(OptionEntry(minutes, 30L, 10L)))
        val settled = HostFeature("a", true, FeatureState.ACTIVE, options = listOf(OptionEntry(minutes, 10L, 10L)))
        assertEquals(setOf("a"), restartPending(listOf(waiting)))
        assertEquals(emptySet<String>(), restartPending(listOf(settled)))
        assertEquals(setOf("a"), restartPending(listOf(HostFeature("a", false, FeatureState.ACTIVE))))
        assertEquals(setOf("a"), restartPending(listOf(HostFeature("a", true, FeatureState.DISABLED))))
        assertEquals(emptySet<String>(), restartPending(listOf(HostFeature("a", true, FeatureState.ACTIVE))))
    }

    @Test fun `a toggle is stored and shown at once`() {
        model.setEnabled("a", true)
        assertEquals(mapOf("a" to true), stored)
        assertEquals(true, model.state.value.features.single().configured)
    }

    @Test fun `an option change is stored and shown as pending`() {
        val minutes = NumberOption("minutes", default = 10, min = 1, max = 120)
        val options = MemoryOptionStore()
        val configurable = HostSettingsViewModel(HostSettingsSession("com.example.host", HostSettingsController(
            listOf(FeatureDefault("a", false)),
            object : FeatureSwitches {
                override fun read(featureId: String, default: Boolean) = default
                override fun write(featureId: String, enabled: Boolean) {}
            },
            listOf(FeatureOptions("a", listOf(minutes), options.load("a", listOf(minutes)))), options,
        ) { listOf(FeatureStatus("a", FeatureState.DISABLED)) }, ui, setup, { HostAccount.Unavailable }, noContacts, diagnostics, config, noEnvironment))
        configurable.setOption("a", "minutes", 30L)
        configurable.setOption("a", "missing", 1L)
        configurable.setOption("a", "minutes", "soon")
        val entry = configurable.state.value.features.single().options.single()
        assertEquals(30L, entry.saved)
        assertTrue(entry.pending)
        assertEquals("30", options.stored["a/minutes"])
        HostSettingsViewModel(null).setOption("a", "minutes", 5L)
    }

    @Test fun `an options dialog saves its changes together and skips what the feature does not take`() {
        val minutes = NumberOption("minutes", default = 10, min = 1, max = 120)
        val chats = ConversationsOption("chats")
        val options = MemoryOptionStore()
        val configurable = HostSettingsViewModel(HostSettingsSession("com.example.host", HostSettingsController(
            listOf(FeatureDefault("a", false)),
            object : FeatureSwitches {
                override fun read(featureId: String, default: Boolean) = default
                override fun write(featureId: String, enabled: Boolean) {}
            },
            listOf(FeatureOptions("a", listOf(minutes, chats), options.load("a", listOf(minutes, chats)))), options,
        ) { listOf(FeatureStatus("a", FeatureState.DISABLED)) }, ui, setup, { HostAccount.Unavailable }, noContacts, diagnostics, config, noEnvironment))
        configurable.setOptions("a", mapOf("minutes" to 30L, "chats" to listOf("7000", "0:1:1:2"), "missing" to 1L))
        val saved = configurable.state.value.features.single().options.associate { it.option.key to it.saved }
        assertEquals(mapOf("minutes" to 30L, "chats" to listOf("7000", "0:1:1:2")), saved)
        assertEquals("7000\n0:1:1:2", options.stored["a/chats"])
    }

    @Test fun `contacts are unavailable without a session`() {
        val error = assertThrows(HostContactsUnavailable::class.java) { runBlocking { HostSettingsViewModel(null).contacts() } }
        assertEquals("No session", error.message)
    }

    @Test fun `failures shown at start are unlocked one by one`() {
        diagnostics.write(FailureRecord("a", FailurePhases.INSTALL, "E", null, "t", 1))
        diagnostics.write(FailureRecord("b", FailurePhases.DEX_FIND, "E", null, "t", 2))
        val fresh = HostSettingsViewModel(HostSettingsSession("com.example.host", controller, ui, setup, { account }, noContacts, diagnostics, config, noEnvironment))
        assertEquals(setOf("a", "b"), fresh.state.value.failures.keys)
        fresh.unlock("a")
        assertEquals(setOf("b"), fresh.state.value.failures.keys)
        assertEquals(setOf("b"), diagnostics.stored.keys)
    }

    @Test fun `cleaning the configuration empties the store`() {
        configValues.put("enabled.a", true)
        configValues.put("failure.a", "record")
        model.cleanConfig()
        assertTrue(configValues.all().isEmpty())
    }

    @Test fun `the failure export is offered only when something failed`() {
        assertFalse(model.hasFailures())
        model.noFailures()
        assertEquals(ConfigNotice.NoFailures, model.state.value.configNotice)
        model.dismissConfigNotice()
        diagnostics.write(FailureRecord("a", FailurePhases.AFTER_CALLBACK, "E", "boom", "t", 3))
        assertTrue(model.hasFailures())
    }

    @Test fun `a failure recorded while the screen was away shows on return`() {
        assertTrue(model.state.value.failures.isEmpty())
        diagnostics.write(FailureRecord("a", FailurePhases.AFTER_CALLBACK, "E", "boom", "t", 3))
        model.refresh()
        assertEquals("boom", model.state.value.failures.getValue("a").message)
    }

    @Test fun `safe mode is stored and shown`() {
        assertEquals(false, model.state.value.safeMode)
        model.setSafeMode(true)
        assertEquals(true, diagnostics.safeMode())
        assertEquals(true, model.state.value.safeMode)
    }

    @Test fun `an unknown feature changes nothing`() {
        model.setEnabled("missing", true)
        assertTrue(stored.isEmpty())
        assertEquals(1, model.state.value.features.size)
    }

    @Test fun `a launch without a session is unavailable and ignores toggles`() {
        val lost = HostSettingsViewModel(null)
        lost.setEnabled("a", true)
        assertEquals(HostSession.UNAVAILABLE, lost.state.value.session)
        assertTrue(lost.state.value.features.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test fun `the appearance is stored and shown at once`() {
        val dark = UiConfig(ThemeChoice.DARK, accent = "#14B8A6", clickHaptic = true)
        model.setUi(dark)
        assertEquals(dark, storedUi)
        assertEquals(dark, model.state.value.ui)
    }

    @Test fun `a screen restored without a session keeps the default appearance and stores nothing`() {
        val lost = HostSettingsViewModel(null)
        lost.setUi(UiConfig(ThemeChoice.DARK))
        assertEquals(UiConfig(), lost.state.value.ui)
        assertEquals(UiConfig(), storedUi)
    }
}
