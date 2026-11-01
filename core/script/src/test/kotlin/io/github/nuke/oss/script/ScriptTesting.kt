package io.github.nuke.oss.script

import io.github.nuke.oss.script.module.CORE_MODULES
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RecordingListener : ScriptSessionListener {
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    val stops: MutableList<ScriptException?> = Collections.synchronizedList(ArrayList())
    val stopped = CompletableFuture<ScriptException?>()

    override fun log(level: ScriptLogLevel, message: String) {
        logs += "$level $message"
    }

    override fun scriptError(where: String, message: String) {
        errors += "$where: $message"
    }

    override fun stopped(failure: ScriptException?) {
        stops += failure
        stopped.complete(failure)
    }

    fun awaitStop(): ScriptException? = stopped.get(5, TimeUnit.SECONDS)

    // Polls because the script writes its logs on its own thread.
    fun awaitLog(text: String, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (logs.any { text in it }) return
            Thread.sleep(5)
        }
        throw AssertionError("No log containing '$text' in $logs")
    }
}

class TestModule(
    override val name: String,
    override val source: String = "",
    private val binder: (ScriptModuleContext) -> Unit = {},
) : ScriptModule {
    override fun bind(context: ScriptModuleContext) = binder(context)
}

fun definition(source: String, permissions: ScriptPermissions = ScriptPermissions(), config: Map<String, Any?> = emptyMap()) =
    ScriptDefinition(
        id = "test.script",
        name = "Test",
        version = "1.0.0",
        versionCode = 1,
        entrySource = source,
        permissions = permissions,
        config = config,
    )

fun ScriptEngine.run(
    source: String,
    listener: ScriptSessionListener = RecordingListener(),
    modules: List<ScriptModule> = emptyList(),
    permissions: ScriptPermissions = ScriptPermissions(),
    config: Map<String, Any?> = emptyMap(),
): ScriptSession = start(definition(source, permissions, config), CORE_MODULES + modules, listener)
