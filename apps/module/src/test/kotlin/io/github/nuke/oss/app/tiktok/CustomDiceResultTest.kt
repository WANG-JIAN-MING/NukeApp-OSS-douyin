package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.tiktok.feature.chat.CustomDiceResult
import io.github.nuke.oss.app.tiktok.feature.chat.DiceContent
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for choosing the dice or rock-paper-scissors result, with stand-ins for the message and the send step.
class CustomDiceResultTest : HookHarness() {
    class Msg(private val kind: Int, private var body: String) {
        fun getMsgType() = kind
        fun getContent() = body
        fun setContent(value: String?) { body = value ?: "" }
    }
    class Chat {
        val sent = ArrayList<String>()
        fun send(message: Msg?, extras: Map<*, *>?) { sent += message!!.getContent() }
    }
    class ValueChat { fun send(message: Msg?): Int = 1 }

    private val logged = ArrayList<String>()
    private val prompts = RecordingPrompts()

    private val send = method(Chat::class.java, "send", Msg::class.java, Map::class.java)

    private fun dice() = CustomDiceResult(resolver(ChatQueries.SEND_MESSAGE to listOf(send)), loader, prompts, Logger { logged += it }, Msg::class.java.name)

    private val diceBody = """{"aweType":507,"display_name":"摇骰子","potential_static_url_list":[""" +
        (1..6).joinToString(",") { """{"static_url":{"uri":"d$it","width":50,"height":60},"static_type":"png"}""" } + "]}"

    @Test fun `a dice message waits for the chosen result and goes out with it`() {
        enable(dice())
        val chat = Chat()
        backend.call(send, chat, Msg(5, diceBody), null)
        assertTrue(chat.sent.isEmpty())
        val pick = prompts.picks.single()
        assertEquals(R.string.host_game_title_dice, pick.title)
        assertEquals(listOf<Any>("1", "2", "3", "4", "5", "6"), pick.items)
        pick.onChosen(3)
        val sent = MiniJson.obj(MiniJson.parse(chat.sent.single()))!!
        assertEquals("d4", MiniJson.obj(sent["url"])!!["uri"])
        assertEquals(50L, sent["width"])
        assertEquals(60L, sent["height"])
        assertEquals("png", sent["image_type"])
    }

    @Test fun `random and an unavailable dialog send the message unchanged`() {
        enable(dice())
        val chat = Chat()
        backend.call(send, chat, Msg(5, diceBody), null)
        prompts.picks.single().onOther()
        assertEquals(listOf(diceBody), chat.sent)
        backend.call(send, chat, Msg(5, diceBody), null)
        prompts.picks.last().onUnavailable()
        assertEquals(2, chat.sent.size)
    }

    @Test fun `cancelling sends nothing`() {
        enable(dice())
        val chat = Chat()
        backend.call(send, chat, Msg(5, diceBody), null)
        prompts.picks.single().onCancel()
        assertTrue(chat.sent.isEmpty())
    }

    @Test fun `other messages are not held back`() {
        enable(dice())
        val chat = Chat()
        backend.call(send, chat, Msg(1, "hello"), null)
        backend.call(send, chat, Msg(5, """{"aweType":1,"display_name":"摇骰子"}"""), null)
        backend.call(send, chat, Msg(5, "not json"), null)
        assertEquals(3, chat.sent.size)
        assertTrue(prompts.picks.isEmpty())
    }

    @Test fun `rock paper scissors offers the three hands`() {
        enable(dice())
        val chat = Chat()
        val body = """{"aweType":507,"display_name":"猜拳","potential_static_url_list":[""" +
            listOf("e589aae58880", "e79fb3e5a4b4", "e5b883").joinToString(",") { """{"static_url":{"uri":"x$it"},"static_type":"webp"}""" } + "]}"
        backend.call(send, chat, Msg(5, body), null)
        val pick = prompts.picks.single()
        assertEquals(R.string.host_game_title_hands, pick.title)
        assertEquals(listOf<Any>(R.string.host_game_scissors, R.string.host_game_rock, R.string.host_game_paper), pick.items)
        pick.onChosen(1)
        val sent = MiniJson.obj(MiniJson.parse(chat.sent.single()))!!
        assertEquals("xe79fb3e5a4b4", MiniJson.obj(sent["url"])!!["uri"])
        assertEquals(100L, sent["width"])
        assertEquals("webp", sent["image_type"])
    }

    @Test fun `a send step that returns a value fails the install`() {
        failsToInstall(
            CustomDiceResult(resolver(ChatQueries.SEND_MESSAGE to listOf(method(ValueChat::class.java, "send", Msg::class.java))), loader, prompts, Logger { logged += it }, Msg::class.java.name),
            "returns int",
        )
    }

    @Test fun `results outside the offered range or an incomplete list give nothing`() {
        assertNull(DiceContent.withDice(diceBody, 0))
        assertNull(DiceContent.withDice(diceBody, 7))
        assertNull(DiceContent.withDice("""{"potential_static_url_list":[]}""", 1))
        assertNull(DiceContent.withHand(diceBody, 2))
        assertNull(DiceContent.withHand(diceBody, 4))
        assertNotNull(DiceContent.withDice(diceBody, 6))
    }

    @Test fun `the json reader round trips nested values and rejects bad documents`() {
        val text = """{"a":[1,2.5,"x\nA",true,null],"b":{"c":-3}}"""
        val parsed = MiniJson.parse(text)
        assertEquals("""{"a":[1,2.5,"x\nA",true,null],"b":{"c":-3}}""", MiniJson.write(parsed))
        assertNull(MiniJson.parse("""{"a":1"""))
        assertNull(MiniJson.parse("""{"a":1} x"""))
        assertNull(MiniJson.parse(""))
    }
}
