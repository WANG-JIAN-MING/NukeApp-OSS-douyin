package io.github.nuke.oss.common

import org.junit.Assert.*
import org.junit.Test
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ProcessEntryGateTest {
    @Test fun `separate gate instances cannot attach another backend`() {
        val properties = Properties()
        val first = ProcessEntryGate(properties)
        assertTrue(first.claim("zygisk"))
        assertTrue(first.claim("zygisk"))
        assertFalse(ProcessEntryGate(properties).claim("libxposed102"))
        assertFalse(ProcessEntryGate(properties).claim("zygisk"))
        assertFalse(first.claim("xposed82"))
    }

    @Test fun `concurrent entries publish exactly one owner`() {
        val properties = Properties()
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val workers = (1..12).map {
            thread {
                start.await()
                if (ProcessEntryGate(properties).claim(listOf("xposed82", "libxposed102", "zygisk")[it % 3])) winners.incrementAndGet()
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, winners.get())
    }

    @Test fun `failure does not release ownership for another mode or module copy`() {
        val properties = Properties()
        val gate = ProcessEntryGate(properties)
        assertThrows(IllegalStateException::class.java) {
            check(gate.claim("libxposed102"))
            error("partial initialization")
        }
        assertFalse(ProcessEntryGate(properties).claim("xposed82"))
        assertFalse(ProcessEntryGate(properties).claim("zygisk"))
        assertTrue(gate.claim("libxposed102"))
    }

    @Test fun `invalid modes and malformed owners never clobber an existing claim`() {
        val properties = Properties()
        val gate = ProcessEntryGate(properties)
        assertThrows(IllegalArgumentException::class.java) { gate.claim("unknown") }
        assertTrue(properties.isEmpty())
        properties[ProcessEntryGate.KEY] = 42
        assertFalse(gate.claim("zygisk"))
        assertEquals(42, properties[ProcessEntryGate.KEY])
    }

    @Test fun `boot properties coordinate separate copies of the gate class`() {
        val parent = requireNotNull(javaClass.classLoader)
        val binary = ProcessEntryGate::class.java.name
        val bytes = parent.getResourceAsStream(binary.replace('.', '/') + ".class")!!.use { it.readBytes() }
        fun isolated() = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != binary) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
            }
        }.loadClass(binary)
        val properties = Properties()
        val first = isolated()
        val second = isolated()
        assertNotSame(first, second)
        val a = first.getConstructor(Properties::class.java, String::class.java).newInstance(properties, "first")
        val b = second.getConstructor(Properties::class.java, String::class.java).newInstance(properties, "second")
        assertEquals(true, first.getMethod("claim", String::class.java).invoke(a, "xposed82"))
        assertEquals(false, second.getMethod("claim", String::class.java).invoke(b, "zygisk"))
        assertEquals(false, second.getMethod("claim", String::class.java).invoke(b, "libxposed102"))
        assertEquals(false, second.getMethod("claim", String::class.java).invoke(b, "xposed82"))
        assertEquals(true, first.getMethod("claim", String::class.java).invoke(a, "xposed82"))
    }
    @Test fun `three modes race across distinct ClassLoaders under one boot monitor`() {
        val parent = requireNotNull(javaClass.classLoader)
        val binary = ProcessEntryGate::class.java.name
        val bytes = parent.getResourceAsStream(binary.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val properties = Properties()
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until 12).map { index ->
            val loader = object : ClassLoader(parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name != binary) return super.loadClass(name, resolve)
                    return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
                }
            }
            val type = loader.loadClass(binary)
            val gate = type.getConstructor(Properties::class.java, String::class.java).newInstance(properties, "gate-$index")
            val claim = type.getMethod("claim", String::class.java)
            val mode = listOf("xposed82", "libxposed102", "zygisk")[index % 3]
            thread {
                try {
                    start.await()
                    if (claim.invoke(gate, mode) == true) {
                        winners.incrementAndGet()
                        assertEquals(true, claim.invoke(gate, mode))
                    }
                } catch (error: Throwable) { failures += error }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(1, winners.get())
    }

}
