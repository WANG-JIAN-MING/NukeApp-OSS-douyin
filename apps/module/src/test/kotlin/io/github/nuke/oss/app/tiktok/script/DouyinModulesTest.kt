package io.github.nuke.oss.app.tiktok.script

import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReaderTest
import io.github.nuke.oss.script.ScriptDefinition
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptException
import io.github.nuke.oss.script.ScriptLogLevel
import io.github.nuke.oss.script.ScriptModule
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.ScriptSessionListener
import io.github.nuke.oss.script.module.CORE_MODULES
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class DouyinModulesTest {
    private val engine = ScriptEngine()
    private val logs = Collections.synchronizedList(ArrayList<String>())
    private val sent = Collections.synchronizedList(ArrayList<String>())

    @After
    fun close() = engine.close()

    private fun run(source: String, modules: List<ScriptModule>, host: Set<String>): List<String> {
        val listener = object : ScriptSessionListener {
            override fun log(level: ScriptLogLevel, message: String) { logs += message }
            override fun scriptError(where: String, message: String) { logs += "error $message" }
            override fun stopped(failure: ScriptException?) = Unit
        }
        engine.start(
            ScriptDefinition("com.example.douyin", "Douyin", "1", 1, "import { info } from \"nuke:log\";\n$source\ninfo(\"done\");", permissions = ScriptPermissions(host = host)),
            CORE_MODULES + modules,
            listener,
        ).awaitStart()
        val deadline = System.currentTimeMillis() + 5_000
        while ("done" !in logs && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return logs.filter { it != "done" }
    }

    private val sender = object : ContentSender {
        override fun send(uid: String, content: Any, timeoutMillis: Long): SendResult {
            sent += "user $uid $content $timeoutMillis"
            return if (uid == "1") SendResult.Sent("c1", 7L, "acked") else SendResult.Unknown("no answer")
        }

        override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long): SendResult {
            sent += "chat $conversationId $content $timeoutMillis"
            return SendResult.Failed("blocked")
        }
    }

    @Test
    fun sendsTextAndReportsTheHostAnswer() {
        val logs = run(
            """
            import { sendText } from "nuke:douyin/im";
            const ok = await sendText({ uid: "1" }, "hi");
            info(ok.status, ok.clientMessageId, ok.serverMessageId, ok.detail);
            info((await sendText({ uid: "2" }, "hi", { timeoutMs: 500 })).status);
            info((await sendText({ conversationId: "0:1:2" }, "yo")).detail);
            for (const target of [{}, { uid: "1", conversationId: "x" }]) {
              try { await sendText(target, "x"); } catch (e) { info(e.code); }
            }
            try { await sendText({ uid: "1" }, ""); } catch (e) { info(e.code); }
            """,
            listOf(DouyinImModule(sender, { "text:$it" })),
            setOf(DOUYIN_MESSAGING),
        )
        assertEquals(listOf("sent c1 7 acked", "unknown", "blocked", "INVALID_ARGUMENT", "INVALID_ARGUMENT", "INVALID_ARGUMENT"), logs)
        assertEquals(listOf("user 1 text:hi 10000", "user 2 text:hi 500", "chat 0:1:2 text:yo 10000"), sent.toList())
    }

    @Test
    fun sendingNeedsItsGrantAndAnAdaptedHost() {
        val unadapted = DouyinImModule(sender, { throw DexQueryUnavailable("TEXT_CONTENT was not located") })
        assertEquals(
            listOf("PermissionError"),
            run("""import { sendText } from "nuke:douyin/im"; try { await sendText({ uid: "1" }, "hi"); } catch (e) { info(e.name); }""",
                listOf(unadapted), emptySet()),
        )
        logs.clear()
        assertEquals(
            listOf("UNAVAILABLE"),
            run("""import { sendText } from "nuke:douyin/im"; try { await sendText({ uid: "1" }, "hi"); } catch (e) { info(e.code); }""",
                listOf(unadapted), setOf(DOUYIN_MESSAGING)),
        )
        assertEquals(emptyList<String>(), sent.toList())
    }

    @Test
    fun readsTheCurrentAccount() {
        val service = DouyinAccountReaderTest.Service()
        val module = DouyinAccountModule(DouyinAccountReader({ service }) {})
        val logs = run(
            """
            import { current } from "nuke:douyin/account";
            const account = current();
            info(account.state, account.uid, account.nickname);
            """,
            listOf(module),
            setOf(DOUYIN_ACCOUNT),
        )
        assertEquals(listOf("signedIn 9007199254740993 nick"), logs)
    }
}
