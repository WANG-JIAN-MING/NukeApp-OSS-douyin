package io.github.nuke.oss.app.shared

import io.github.nuke.oss.app.shared.data.FlatJson
import org.junit.Assert.*
import org.junit.Test

class FlatJsonTest {
    @Test fun `top level strings are read and other members are skipped`() {
        val json = """ {"order_no":"A1","n":12,"ok":true,"nil":null,"nested":{"order_no":"inner","x":[1,"a,b}",{"y":"z"}]},"list":["q"],"open_command":"hi \"you\" 中\n"} """
        assertEquals(mapOf("order_no" to "A1", "open_command" to "hi \"you\" 中\n"), FlatJson.strings(json))
    }

    @Test fun `an empty object is an empty map and bad input is null`() {
        assertEquals(emptyMap<String, String>(), FlatJson.strings(" { } "))
        assertNull(FlatJson.strings(""))
        assertNull(FlatJson.strings("[]"))
        assertNull(FlatJson.strings("""{"a":"b""""))
        assertNull(FlatJson.strings("""{"a":"b"} trailing"""))
        assertNull(FlatJson.strings("""{"a" "b"}"""))
        assertNull(FlatJson.strings("""{"a":"\q"}"""))
        assertNull(FlatJson.strings("""{"a":"\u12"}"""))
    }

    @Test fun `scalars next to the closing brace and spaces before commas are handled`() {
        assertEquals(mapOf("b" to "x"), FlatJson.strings("""{"a":1 , "b":"x"}"""))
        assertEquals(mapOf("b" to "x"), FlatJson.strings("""{"a":{"k":1} , "b":"x"}"""))
        assertEquals(emptyMap<String, String>(), FlatJson.strings("""{"a":-1.5e3}"""))
    }
}
