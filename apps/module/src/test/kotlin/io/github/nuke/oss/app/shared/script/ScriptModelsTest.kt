package io.github.nuke.oss.app.shared.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptModelsTest {
    private fun permissions(java: Boolean = false, hook: Boolean = false, network: Boolean = false, host: List<String> = emptyList()) =
        ManifestPermissions(
            anyNetwork = network, networkOrigins = emptyList(), privateNetwork = false, java = java, hook = hook,
            externalFiles = false, hostFiles = false, filesReason = null, host = host,
        )

    @Test
    fun grantsFollowTheDangerousCapabilitiesOnly() {
        val plain = permissions()
        assertEquals(emptyList<String>(), plain.dangerous())
        assertEquals(permissions(network = true).grantDigest(), plain.grantDigest())
        assertNotEquals(permissions(java = true).grantDigest(), permissions(hook = true).grantDigest())
        assertEquals(
            permissions(host = listOf("b.x", "a.y")).grantDigest(),
            permissions(host = listOf("a.y", "b.x")).grantDigest(),
        )
    }

    @Test
    fun configValuesFallBackToDefaultsAndDropWhatNoLongerFits() {
        val schema = ScriptConfigSchema.parse(
            """{"fields":[
              {"key":"name","type":"string","title":"Name","default":"ana","maxLength":5},
              {"key":"count","type":"integer","title":"Count","min":1,"max":3,"default":2},
              {"key":"mode","type":"select","title":"Mode","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]},
              {"key":"on","type":"boolean","title":"On"}]}""",
        )
        assertEquals(mapOf("name" to "ana", "count" to 2L), schema.values(emptyMap()))
        assertEquals(
            mapOf("name" to "bo", "count" to 3L, "mode" to "b", "on" to true),
            schema.values(mapOf("name" to "bo", "count" to 3L, "mode" to "b", "on" to true, "gone" to 1)),
        )
        assertEquals(
            mapOf("name" to "ana", "count" to 2L),
            schema.values(mapOf("name" to "too long", "count" to 9L, "mode" to "z", "on" to "yes")),
        )
        val count = schema.fields[1]
        assertEquals(ConfigProblem.BELOW_MIN, count.problem(0L))
        assertEquals(ConfigProblem.TYPE, count.problem(1.5))
        assertNull(count.problem(1.0))
        assertEquals(ConfigProblem.TOO_LONG, schema.fields[0].problem("abcdef"))
    }

    @Test
    fun scriptStatesRoundTrip() {
        val values = HashMap<String, String>()
        val store = KeyedScriptStates(values::get) { key, value -> if (value == null) values.remove(key) else values[key] = value }
        assertEquals(ScriptState(), store.read("a.b"))
        val state = ScriptState(enabled = true, grant = "abc", config = mapOf("n" to 1L, "s" to "x"))
        store.write("a.b", state)
        assertEquals(state, store.read("a.b"))
        assertEquals(setOf("script.a.b"), values.keys)
        store.remove("a.b")
        assertEquals(ScriptState(), store.read("a.b"))
    }
}
