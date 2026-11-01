package io.github.nuke.oss.app.tiktok.script

import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.hook.api.HookOwner
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.script.ScriptDefinition
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptLogLevel
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptSessionListener
import io.github.nuke.oss.script.java.HookModule
import io.github.nuke.oss.script.java.JavaModule
import io.github.nuke.oss.script.module.CORE_MODULES
import io.github.nuke.oss.script.module.FsModule
import io.github.nuke.oss.script.module.HttpModule
import io.github.nuke.oss.script.module.ScriptUi
import io.github.nuke.oss.script.module.UiModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory

// The SDK's type declarations and the modules a Douyin script session gets must name the same modules and exports.
// Exports starting with "__" are internal helpers between modules and are left out of the SDK.
class ScriptApiContractTest {
    private val engine = ScriptEngine()
    private val scope = FeatureScope(HookOwner("script.contract", "gen"), DefaultHookService(FakeHookBackend()))

    @After
    fun close() {
        engine.close()
        scope.close()
    }

    // Built like TikTokHostAdapter.registerScripts builds them; keep the two lists in step.
    private fun sessionModules(): List<ScriptModule> {
        val java = JavaModule(javaClass.classLoader!!)
        val ui = object : ScriptUi {
            override suspend fun toast(script: String, message: String, long: Boolean) = Unit
            override suspend fun alert(script: String, title: String?, message: String) = Unit
            override suspend fun confirm(script: String, title: String?, message: String, confirm: String?, cancel: String?) = false
        }
        val sender = object : ContentSender {
            override fun send(uid: String, content: Any, timeoutMillis: Long) = SendResult.Unknown("")
            override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long) = SendResult.Unknown("")
        }
        return CORE_MODULES + listOf(
            java,
            HookModule(java, scope.hooks),
            HttpModule(),
            FsModule(createTempDirectory("data").toFile(), createTempDirectory("host").toFile()),
            UiModule(ui),
            DouyinAccountModule(DouyinAccountReader({ null }) {}),
            DouyinImModule(sender, { it }),
        )
    }

    private fun declared(): Map<String, Set<String>> {
        val types = File(System.getProperty("user.dir"), "../../sdk/script-sdk/types/index.d.ts").readText()
        val modules = Regex("""(?m)^declare module "([^"]+)" \{\n(.*?)^\}""", RegexOption.DOT_MATCHES_ALL).findAll(types)
        return modules.associate { match ->
            match.groupValues[1] to Regex("""(?m)^  export (?:function|const|class) (\w+)""").findAll(match.groupValues[2])
                .map { it.groupValues[1] }.toSet()
        }
    }

    private fun provided(modules: List<ScriptModule>): Map<String, Set<String>> {
        val lines = Collections.synchronizedList(ArrayList<String>())
        val imports = modules.mapIndexed { index, module -> "import * as m$index from \"${module.name}\";" }
        val report = modules.mapIndexed { index, module ->
            "info(\"${module.name}=\" + Object.keys(m$index).filter((name) => !name.startsWith(\"__\")).sort().join(\",\"));"
        }
        val listener = object : ScriptSessionListener {
            override fun log(level: ScriptLogLevel, message: String) { lines += message }
            override fun scriptError(where: String, message: String) { lines += "error $message" }
            override fun stopped(failure: ScriptException?) = Unit
        }
        engine.start(
            ScriptDefinition("com.example.contract", "Contract", "1", 1,
                (listOf("import { info } from \"nuke:log\";") + imports + report).joinToString("\n")),
            modules,
            listener,
        ).awaitStart()
        val deadline = System.currentTimeMillis() + 5_000
        while (lines.size < modules.size && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return lines.associate { line ->
            val (name, exports) = line.split("=", limit = 2)
            name to exports.split(",").filter(String::isNotEmpty).toSet()
        }
    }

    @Test
    fun theSdkDeclaresExactlyWhatSessionsProvide() {
        val declared = declared()
        val provided = provided(sessionModules())
        assertEquals("modules", provided.keys.sorted(), declared.keys.sorted())
        for (module in provided.keys.sorted()) {
            assertEquals(module, provided.getValue(module).sorted(), declared.getValue(module).sorted())
        }
    }
}
