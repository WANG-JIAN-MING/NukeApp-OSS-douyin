package io.github.nuke.oss.script

import com.sun.net.httpserver.HttpServer
import io.github.nuke.oss.script.module.FsModule
import io.github.nuke.oss.script.module.HttpModule
import io.github.nuke.oss.script.module.ScriptUi
import io.github.nuke.oss.script.module.UiModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.io.path.createTempDirectory

class HostModulesTest {
    private val engine = ScriptEngine()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val port get() = server.address.port

    init {
        server.createContext("/hello") { exchange ->
            val body = "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("X-Test")} ${exchange.requestBody.readBytes().decodeToString()}"
                .toByteArray()
            exchange.responseHeaders.add("X-Answer", "yes")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/json") { exchange ->
            val body = """{"n":1,"list":["a"]}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", exchange.requestURI.query ?: "/hello")
            exchange.sendResponseHeaders(303, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun close() {
        engine.close()
        server.stop(0)
    }

    private fun logsOf(body: String, modules: List<ScriptModule>, permissions: ScriptPermissions): List<String> {
        val listener = RecordingListener()
        engine.run(
            """
            import { info } from "nuke:log";
            $body
            """,
            listener,
            modules,
            permissions,
        ).awaitStart()
        listener.awaitLog("done")
        return listener.logs.map { it.removePrefix("INFO ") }.filter { it != "done" }
    }

    @Test
    fun fetchesFromDeclaredOriginsAndFollowsRedirects() {
        val origin = "http://127.0.0.1:$port"
        val logs = logsOf(
            """
            import { fetch } from "nuke:http";
            const hello = await fetch("$origin/hello", { method: "post", headers: { "X-Test": "h" }, body: "payload" });
            info(hello.status, hello.ok, hello.headers["x-answer"], hello.text());
            const json = await fetch("$origin/json");
            info(json.status, json.ok, json.json().list[0]);
            const moved = await fetch("$origin/moved", { method: "POST", body: "x" });
            info(moved.status, moved.url.endsWith("/hello"), moved.text());
            info("done");
            """,
            listOf(HttpModule()),
            ScriptPermissions(networkOrigins = listOf(origin), privateNetwork = true),
        )
        assertEquals(listOf("200 true yes POST h payload", "404 false a", "200 true GET null "), logs)
    }

    @Test
    fun refusesOriginsAndPrivateAddressesTheScriptDidNotDeclare() {
        val origin = "http://127.0.0.1:$port"
        val logs = logsOf(
            """
            import { fetch } from "nuke:http";
            for (const url of ["$origin/hello", "http://localhost:$port/hello", "ftp://127.0.0.1/x", "$origin/moved?http://example.com/"]) {
              try { await fetch(url); info("fetched"); } catch (e) { info(e.code); }
            }
            info("done");
            """,
            listOf(HttpModule(resolve = { listOf(InetAddress.getLoopbackAddress()) })),
            ScriptPermissions(networkOrigins = listOf(origin, "http://*.example.com")),
        )
        assertEquals(listOf("PERMISSION_DENIED", "PERMISSION_DENIED", "INVALID_ARGUMENT", "PERMISSION_DENIED"), logs)
        val noNetwork = logsOf(
            """
            import { fetch } from "nuke:http";
            try { await fetch("$origin/hello"); } catch (e) { info(e.name); }
            info("done");
            """,
            listOf(HttpModule()),
            ScriptPermissions(),
        )
        assertEquals(listOf("PermissionError"), noNetwork)
    }

    @Test
    fun filesStayInsideTheirRootsAndQuota() {
        val data = createTempDirectory("data").toFile()
        val host = createTempDirectory("host").toFile()
        File(host, "shared_prefs").mkdirs()
        File(host, "shared_prefs/a.xml").writeText("<map/>")
        val logs = logsOf(
            """
            import { data, host } from "nuke:fs";
            await data.writeText("notes/a.txt", "one");
            await data.appendText("notes/a.txt", "+two");
            info(await data.readText("notes/a.txt"), await data.exists("notes/a.txt"), await data.exists("missing"));
            await data.writeBytes("b.bin", new Uint8Array([1, 2]));
            info(Array.from(await data.readBytes("b.bin")).join(","), (await data.list("")).map((f) => f.name + ":" + f.directory).join(" "));
            for (const bad of ["../escape", "/abs", "a\\b", ""]) {
              try { await data.readText(bad); info("read", bad); } catch (e) { info(e.code); }
            }
            try { await data.writeBytes("big.bin", new Uint8Array(3000)); } catch (e) { info(e.code); }
            await data.remove("notes");
            info(await data.exists("notes"));
            info(await host.readText("shared_prefs/a.xml"));
            info("done");
            """,
            listOf(FsModule(data, host, quotaBytes = 2048)),
            ScriptPermissions(hostFiles = true),
        )
        assertEquals(
            listOf(
                "one+two true false",
                "1,2 b.bin:false notes:true",
                "INVALID_ARGUMENT", "INVALID_ARGUMENT", "INVALID_ARGUMENT", "INVALID_ARGUMENT",
                "LIMIT_EXCEEDED",
                "false",
                "<map/>",
            ),
            logs,
        )
        assertFalse(File(data.parentFile, "escape").exists())
    }

    @Test
    fun hostFilesNeedTheirPermissionAndLinksCannotEscape() {
        val data = createTempDirectory("data").toFile()
        val outside = createTempDirectory("outside").toFile()
        File(outside, "secret.txt").writeText("secret")
        java.nio.file.Files.createSymbolicLink(File(data, "link").toPath(), outside.toPath())
        val logs = logsOf(
            """
            import { data, host } from "nuke:fs";
            try { await host.list(); } catch (e) { info(e.name); }
            try { await data.readText("link/secret.txt"); } catch (e) { info(e.code); }
            info("done");
            """,
            listOf(FsModule(data, createTempDirectory("host").toFile())),
            ScriptPermissions(),
        )
        assertEquals(listOf("PermissionError", "PERMISSION_DENIED"), logs)
    }

    @Test
    fun uiCallsReachTheHostWithTheScriptName() {
        val calls = Collections.synchronizedList(ArrayList<String>())
        val ui = object : ScriptUi {
            override suspend fun toast(script: String, message: String, long: Boolean) { calls += "toast $script $message $long" }
            override suspend fun alert(script: String, title: String?, message: String) { calls += "alert $script $title $message" }
            override suspend fun confirm(script: String, title: String?, message: String, confirm: String?, cancel: String?): Boolean {
                calls += "confirm $script $title $message $confirm $cancel"
                return message == "yes?"
            }
        }
        val logs = logsOf(
            """
            import { toast, alert, confirm } from "nuke:ui";
            await toast("hi");
            await toast("long", { long: true });
            await alert({ title: "T", message: "M" });
            info(await confirm("yes?"), await confirm({ title: "Q", message: "no?", confirmText: "Go", cancelText: "Stop" }));
            info("done");
            """,
            listOf(UiModule(ui)),
            ScriptPermissions(),
        )
        assertEquals(listOf("true false"), logs)
        assertEquals(
            listOf("toast Test hi false", "toast Test long true", "alert Test T M", "confirm Test null yes? null null", "confirm Test Q no? Go Stop"),
            calls.toList(),
        )
        assertTrue(true)
    }
}
