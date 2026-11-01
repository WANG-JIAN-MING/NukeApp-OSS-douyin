package io.github.nuke.oss.script.java

import io.github.nuke.oss.script.RecordingListener
import io.github.nuke.oss.script.ScriptEngine
import io.github.nuke.oss.script.ScriptPermissions
import io.github.nuke.oss.script.run
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaModuleTest {
    private val engine = ScriptEngine()

    @After
    fun closeEngine() = engine.close()

    private fun logsOf(body: String, permissions: ScriptPermissions = ScriptPermissions(java = true)): List<String> {
        val listener = RecordingListener()
        engine.run(
            """
            import { info } from "nuke:log";
            import { use, array, isJava, JavaObject, JavaClass } from "nuke:java";
            const Sample = use("${Sample::class.java.name}");
            $body
            info("done");
            """,
            listener,
            listOf(JavaModule(javaClass.classLoader)),
            permissions,
        ).awaitStart()
        listener.awaitLog("done")
        return listener.logs.map { it.removePrefix("INFO ") }.filter { it != "done" }
    }

    @Test
    fun constructsCallsAndReadsFields() {
        val logs = logsOf(
            """
            const sample = Sample.new("ana");
            info(sample instanceof JavaObject, sample.className, Sample instanceof JavaClass, Sample.name);
            info(sample.call("greet", "bo"), sample.get("name"), sample.get("secret"));
            sample.set("count", 7);
            info(sample.get("count"), Sample.get("CONSTANT"), Sample.call("create", "cy").get("name"));
            info(sample.call("nothing"), sample.call("same", 5), isJava(sample), isJava({}));
            """,
        )
        assertEquals(
            listOf(
                "true ${Sample::class.java.name} true ${Sample::class.java.name}",
                "hi bo from ana ana hidden",
                "7 const cy",
                "null 5 true false",
            ),
            logs,
        )
    }

    @Test
    fun choosesOverloadsByArgument() {
        val logs = logsOf(
            """
            const sample = Sample.new("ana");
            info(sample.call("pick", 3), sample.call("pick", 2.5), sample.call("pick", "s"), sample.call("pick", Sample.new("bo")));
            const exact = Sample.method("pick", ["double"]);
            info(sample.invoke(exact, 4), Sample.ctor(["java.lang.String"]).className);
            try { sample.call("pick", true); } catch (e) { info(e.code, e.message.startsWith("No method")); }
            try { Sample.method("pick"); } catch (e) { info(e.code, e.message.includes("overloaded")); }
            """,
        )
        assertEquals(
            listOf(
                "int:3 double:2.5 string:s sample:bo",
                "double:4.0 java.lang.reflect.Constructor",
                "INVALID_ARGUMENT true",
                "INVALID_ARGUMENT true",
            ),
            logs,
        )
    }

    @Test
    fun convertsCollectionsAndBytes() {
        val logs = logsOf(
            """
            const sample = Sample.new("ana");
            const items = sample.call("items");
            info(items.className, items.call("size"), items.toArray().join(","));
            const bytes = sample.call("bytes");
            info(bytes.className, Array.from(bytes.toBytes()).join(","));
            info(sample.call("sum", [1, 2, 3]), sample.call("sum", array("int", [4, 5])));
            info(array("java.lang.String", ["x", "y"]).toArray().join("+"));
            """,
        )
        assertEquals(listOf("java.util.Arrays\$ArrayList 2 a,b", "[B 1,2,3", "6 9", "x+y"), logs)
    }

    @Test
    fun keepsIdentityAndReportsJavaErrors() {
        val logs = logsOf(
            """
            const sample = Sample.new("ana");
            const again = sample.call("self");
            info(sample.equals(again), sample.hashCode() === again.hashCode(), sample.getClass().name);
            try { sample.call("fail"); } catch (e) { info(e.name, e.code, e.javaClass, e.message); }
            try { use("no.such.Type"); } catch (e) { info(e.code, e.message); }
            try { Sample.call("greet", "x"); } catch (e) { info(e.code); }
            """,
        )
        assertEquals(
            listOf(
                "true true ${Sample::class.java.name}",
                "JavaError JAVA_ERROR java.lang.IllegalStateException java.lang.IllegalStateException: boom",
                "JAVA_ERROR Class not found: no.such.Type",
                "INVALID_ARGUMENT",
            ),
            logs,
        )
    }

    @Test
    fun requiresTheJavaPermission() {
        val listener = RecordingListener()
        engine.run(
            """
            import { info } from "nuke:log";
            import { use } from "nuke:java";
            try { use("java.lang.String"); } catch (e) { info(e.name, e.code); }
            info("done");
            """,
            listener,
            listOf(JavaModule(javaClass.classLoader)),
        ).awaitStart()
        listener.awaitLog("done")
        assertTrue(listener.logs.toString(), listener.logs.contains("INFO PermissionError PERMISSION_DENIED"))
    }

    @Test
    fun releasesHandlesOnceWrappersAreCollected() {
        val java = JavaModule(javaClass.classLoader)
        val listener = RecordingListener()
        val session = engine.run(
            """
            import { info } from "nuke:log";
            import { use } from "nuke:java";
            const Sample = use("${Sample::class.java.name}");
            globalThis.kept = Sample.new("kept");
            __nuke.on("churn", () => {
              for (let i = 0; i < 5000; i++) Sample.new("n" + i).get("name");
              info("churned");
            });
            __nuke.on("check", () => info("kept", globalThis.kept.get("name")));
            """,
            listener,
            listOf(java),
            ScriptPermissions(java = true),
        )
        session.awaitStart()
        session.emit("churn", null)
        listener.awaitLog("churned")
        val deadline = System.currentTimeMillis() + 5_000
        while (java.handles.size() > 100 && System.currentTimeMillis() < deadline) {
            session.emit("check", null)
            Thread.sleep(50)
        }
        assertTrue("handles still held: ${java.handles.size()}", java.handles.size() <= 100)
        listener.awaitLog("kept kept")
    }
}
