package io.github.nuke.oss.app.shared.settings

import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.FeatureStatus
import org.junit.Assert.*
import org.junit.Test

class OptionsTest {
    private val toggle = ToggleOption("block-ads", default = true)
    private val choice = ChoiceOption("scheme", listOf("host", "module", "off"), default = "module")
    private val multi = MultiOption("hidden", listOf("home", "mall", "shop"), default = setOf("mall"))
    private val number = NumberOption("minutes", default = 10, min = 1, max = 120)
    private val lines = LinesOption("words")
    private val text = TextOption("bubble", maxLength = 6)
    private val all = listOf(toggle, choice, multi, number, lines, text)

    @Test fun `missing values read as the defaults`() {
        val values = MemoryOptionStore().load("f", all)
        assertEquals(true, values.toggle("block-ads"))
        assertEquals("module", values.choice("scheme"))
        assertEquals(setOf("mall"), values.multi("hidden"))
        assertEquals(10L, values.number("minutes"))
        assertEquals(emptyList<String>(), values.lines("words"))
        assertEquals("", values.text("bubble"))
        assertEquals(values, OptionValues.defaults(all))
    }

    @Test fun `unreadable stored values never stop a load`() {
        val store = MemoryOptionStore()
        store.write("f", "block-ads", "maybe")
        store.write("f", "scheme", "gone")
        store.write("f", "hidden", "home,nowhere,shop")
        store.write("f", "minutes", "9999")
        store.write("f", "words", "a\n\n  b  \n")
        store.write("f", "bubble", "  toolongvalue  ")
        val values = store.load("f", all)
        assertEquals(true, values.toggle("block-ads"))
        assertEquals("module", values.choice("scheme"))
        assertEquals(setOf("home", "shop"), values.multi("hidden"))
        assertEquals(120L, values.number("minutes"))
        assertEquals(listOf("a", "b"), values.lines("words"))
        assertEquals("toolon", values.text("bubble"))
        store.write("f", "minutes", "abc")
        assertEquals(10L, store.load("f", all).number("minutes"))
    }

    @Test fun `picked chats are stored one per line, so a former keyword list keeps its entries`() {
        val chats = ConversationsOption("skip-chats")
        val store = MemoryOptionStore()
        store.write("f", "skip-chats", LinesOption("skip-chats").encode(listOf("0:1:1:2", "7000")))
        assertEquals(listOf("0:1:1:2", "7000"), store.load("f", listOf(chats)).conversations("skip-chats"))
        assertEquals(emptyList<String>(), OptionValues.defaults(listOf(chats)).conversations("skip-chats"))
        val picked = chats.normalize(setOf("7000", " 0:1:1:2 "))
        assertEquals(picked, chats.decode(chats.encode(picked)))
        assertThrows(IllegalArgumentException::class.java) { chats.normalize("7000") }
    }

    @Test fun `an emptied multi choice stays empty instead of returning to its default`() {
        val store = MemoryOptionStore()
        store.write("f", "hidden", multi.encode(multi.normalize(emptySet<String>())))
        assertEquals(emptySet<String>(), store.load("f", all).multi("hidden"))
    }

    @Test fun `every kind survives an encode and decode round trip`() {
        val samples = mapOf<Option, Any>(
            toggle to false, choice to "off", multi to setOf("shop", "home"), number to 42L,
            lines to listOf("one", "two"), text to "hello",
        )
        for ((option, value) in samples) {
            val stored = option.encode(option.normalize(value))
            assertEquals(option.key, option.normalize(value), option.decode(stored))
        }
        assertEquals("home,shop", multi.encode(multi.normalize(setOf("shop", "home"))))
    }

    @Test fun `values from the screen are validated`() {
        assertThrows(IllegalArgumentException::class.java) { toggle.normalize("yes") }
        assertThrows(IllegalArgumentException::class.java) { choice.normalize("gone") }
        assertThrows(IllegalArgumentException::class.java) { multi.normalize(setOf("nowhere")) }
        assertThrows(IllegalArgumentException::class.java) { multi.normalize(listOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { number.normalize("5") }
        assertThrows(IllegalArgumentException::class.java) { lines.normalize("a") }
        assertEquals(1L, number.normalize(-5))
        assertEquals(120L, number.normalize(500L))
        assertEquals(listOf("a", "b", "c"), lines.normalize(listOf(" a ", "", "b\nc")))
        assertEquals("a b", text.normalize(" a\nb "))
        assertEquals("abcdef", text.normalize("abcdefgh"))
    }

    @Test fun `declarations that cannot be stored are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ToggleOption("Bad Key") }
        assertThrows(IllegalArgumentException::class.java) { ChoiceOption("c", listOf("a,b")) }
        assertThrows(IllegalArgumentException::class.java) { ChoiceOption("c", listOf("a", "a")) }
        assertThrows(IllegalArgumentException::class.java) { ChoiceOption("c", listOf("a"), default = "b") }
        assertThrows(IllegalArgumentException::class.java) { MultiOption("m", listOf("a"), default = setOf("b")) }
        assertThrows(IllegalArgumentException::class.java) { NumberOption("n", default = 0, min = 1, max = 2) }
        assertThrows(IllegalArgumentException::class.java) {
            FeatureOptions("f", listOf(ToggleOption("a"), ToggleOption("a")), OptionValues.defaults(emptyList()))
        }
    }

    @Test fun `the registry hands out stored values and remembers what each feature was built with`() {
        val store = MemoryOptionStore()
        store.write("f", "minutes", "30")
        val registry = OptionRegistry(store)
        val running = registry.values("f", listOf(number))
        assertEquals(30L, running.number("minutes"))
        val feature = registry.features().single()
        assertEquals("f", feature.featureId)
        assertEquals(running, feature.running)
    }

    private val store = MemoryOptionStore()
    private val switches = object : FeatureSwitches {
        override fun read(featureId: String, default: Boolean) = default
        override fun write(featureId: String, enabled: Boolean) {}
    }
    private val controller = HostSettingsController(
        listOf(FeatureDefault("f", false), FeatureDefault("plain", false)), switches,
        listOf(FeatureOptions("f", listOf(number, multi), store.load("f", listOf(number, multi)))), store,
    ) { listOf(FeatureStatus("f", FeatureState.ACTIVE)) }

    @Test fun `a saved option differs from the running one until the host restarts`() {
        val before = controller.entries().first { it.id == "f" }.options
        assertTrue(before.none { it.pending })
        val after = controller.setOption("f", "minutes", 45L).first { it.id == "f" }.options
        val minutes = after.first { it.option.key == "minutes" }
        assertEquals(45L, minutes.saved)
        assertEquals(10L, minutes.running)
        assertTrue(minutes.pending)
        assertFalse(after.first { it.option.key == "hidden" }.pending)
        assertEquals("45", store.stored["f/minutes"])
    }

    @Test fun `saving the running value again is not pending`() {
        controller.setOption("f", "minutes", 45L)
        val back = controller.setOption("f", "minutes", 10L).first { it.id == "f" }.options.first { it.option.key == "minutes" }
        assertFalse(back.pending)
    }

    @Test fun `an unknown feature or option is rejected without a write`() {
        assertThrows(IllegalArgumentException::class.java) { controller.setOption("plain", "minutes", 1L) }
        assertThrows(IllegalArgumentException::class.java) { controller.setOption("f", "missing", 1L) }
        assertThrows(IllegalArgumentException::class.java) { controller.setOption("f", "minutes", "soon") }
        assertTrue(store.stored.isEmpty())
        assertTrue(controller.entries().first { it.id == "plain" }.options.isEmpty())
    }

    @Test fun `options for a feature the controller does not know are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostSettingsController(
                listOf(FeatureDefault("f", false)), switches,
                listOf(FeatureOptions("other", listOf(number), OptionValues.defaults(listOf(number)))), store,
            ) { emptyList() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostSettingsController(
                listOf(FeatureDefault("f", false)), switches,
                listOf(FeatureOptions("f", listOf(number), OptionValues.defaults(listOf(number)))), null,
            ) { emptyList() }
        }
    }
}
