package io.github.nuke.oss.backend.lsplant

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class JniLsplantDriverTest {
    @Test fun `static native callback keeps all arguments and copies the array`() {
        val raw = arrayOf<Any?>("value", 7)
        val callback = JniLsplantDriver.NativeCallback(true, 2) { receiver, arguments ->
            assertNull(receiver)
            assertArrayEquals(raw, arguments)
            assertNotSame(raw, arguments)
            arguments[0] = "changed"
            "result"
        }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("pair", String::class.java, Int::class.javaPrimitiveType))
        assertEquals("result", callback.invoke(raw))
        assertEquals("value", raw[0])
    }

    @Test fun `instance native callback separates receiver from arguments`() {
        val receiver = Any()
        val callback = JniLsplantDriver.NativeCallback(false, 1) { actual, arguments ->
            assertSame(receiver, actual)
            assertArrayEquals(arrayOf<Any?>("argument"), arguments)
            null
        }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("instance", String::class.java))
        assertNull(callback.invoke(arrayOf(receiver, "argument")))
    }

    @Test fun `unpublished backup and malformed native arguments fail explicitly`() {
        val callback = JniLsplantDriver.NativeCallback(false, 1) { _, _ -> error("Must not run") }
        assertThrows(IllegalStateException::class.java) { callback.invoke(arrayOf(Any(), "argument")) }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("instance", String::class.java))
        assertThrows(IllegalArgumentException::class.java) { callback.invoke(emptyArray()) }
        assertThrows(IllegalArgumentException::class.java) { callback.invoke(arrayOf(Any(), "a", "b")) }
    }

    @Test fun `missing native registration stays unavailable and does not pretend success`() {
        assertFalse(JniLsplantDriver.initialize())
        assertFalse(JniLsplantDriver.isReady)
        val target = String::class.java.getMethod("length")
        assertThrows(UnsupportedOperationException::class.java) { JniLsplantDriver.install(target) { _, _ -> null } }
    }

    @Test fun `retired native callback invokes accessible private backup and releases business dispatch`() {
        var calls = 0
        val callback = JniLsplantDriver.NativeCallback(true, 2) { _, _ ->
            calls++
            "hooked"
        }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("pair", String::class.java, Int::class.javaPrimitiveType))
        assertEquals("hooked", callback.invoke(arrayOf("value", 7)))
        callback.retire()
        callback.retire()
        assertEquals("value:7", callback.invoke(arrayOf("value", 7)))
        assertEquals(1, calls)
    }

    @Test fun `retired native callback retains instance receiver and explicit null`() {
        val callback = JniLsplantDriver.NativeCallback(false, 1) { _, _ -> error("retired callback") }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("nullable", String::class.java))
        callback.retire()
        assertNull(callback.invoke(arrayOf(BackupTargets(), "value")))
    }

    @Test fun `retired native callback unwraps only reflection layer and preserves target throwable`() {
        val callback = JniLsplantDriver.NativeCallback(true, 0) { _, _ -> error("retired callback") }
        callback.publishBackup(BackupTargets::class.java.getDeclaredMethod("fail"))
        callback.retire()
        val error = assertThrows(InvocationTargetException::class.java) { callback.invoke(emptyArray()) }
        assertSame(BackupTargets.expected, error)
    }

    @Test fun `constructor backup runs on the receiver through the constructor runner`() {
        val receiver = Any()
        val runs = ArrayList<Triple<java.lang.reflect.Constructor<*>, Any?, List<Any?>>>()
        val callback = JniLsplantDriver.NativeCallback(false, 2, runConstructor = { constructor, actual, arguments ->
            runs += Triple(constructor, actual, arguments.toList())
        }) { actual, arguments ->
            assertSame(receiver, actual)
            assertArrayEquals(arrayOf<Any?>("value", 7), arguments)
            null
        }
        val constructor = Built::class.java.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
        callback.publishBackup(constructor)
        assertNull(callback.invoke(arrayOf(receiver, "value", 7)))
        callback.retire()
        assertNull(callback.invoke(arrayOf(receiver, "value", 7)))
        assertEquals(listOf(Triple<java.lang.reflect.Constructor<*>, Any?, List<Any?>>(constructor, receiver, listOf("value", 7))), runs)
    }

    @Test fun `constructor parameters are described with JVM type letters`() {
        val constructor = Built::class.java.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType, Byte::class.javaPrimitiveType, Char::class.javaPrimitiveType, Short::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            String::class.java, IntArray::class.java,
        )
        assertEquals("ZBCSIJFDLL", String(JniLsplantDriver.parameterKinds(constructor)))
    }

    @Suppress("UNUSED_PARAMETER")
    private class Built {
        constructor(value: String, number: Int)
        constructor(z: Boolean, b: Byte, c: Char, s: Short, i: Int, j: Long, f: Float, d: Double, text: String, ints: IntArray)
    }

    private class BackupTargets {
        private fun instance(value: String) = "instance:$value"
        private fun nullable(value: String): String? = if (value.isNotEmpty()) null else "empty"

        companion object {
            val expected = InvocationTargetException(IllegalStateException("nested"), "deliberate")
            @JvmStatic private fun pair(value: String, number: Int) = "$value:$number"
            @JvmStatic private fun fail(): Any? = throw expected
        }
    }
}
