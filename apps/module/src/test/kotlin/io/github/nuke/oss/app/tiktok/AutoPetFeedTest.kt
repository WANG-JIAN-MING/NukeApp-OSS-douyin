package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClaimNotDispatchedException
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClaimTransportException
import io.github.nuke.oss.app.tiktok.feature.chat.pet.AutoPetFeed
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetApiException
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetBinding
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedAvailability
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedCodec
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedEngine
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedExecutor
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedFileStore
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedGuard
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedJournal
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedOutcome
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedPlan
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedPolicy
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedState
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedStatus
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedStore
import io.github.nuke.oss.app.tiktok.feature.chat.pet.PetFeedTransport
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.core.Logger
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for automatic pet feeding: the response codec, the journal, the engine's no-repeat rules and the
// feature's round, with a stand-in for the host's pet requests.
class AutoPetFeedTest : HookHarness() {
    object Chats {
        @JvmStatic fun conversationId(uid: String) = "0:1:42:$uid"
    }

    internal class MemoryStore(var state: PetFeedState = PetFeedState("42")) : PetFeedStore {
        var writes = 0
        override fun read() = state
        override fun write(state: PetFeedState) { this.state = state; writes++ }
    }

    // The pet service of one friend. read answers the feeding state; a feeding answers feedAnswer unless it fails.
    internal inner class FakePets : PetFeedTransport {
        var account = 42L
        var read: () -> String = { response(action(EXPIRED_START, EXPIRED_END)) }
        var feedAnswer: () -> String = { response(action(NOW_S, NOW_S + 3600)) }
        var afterSend: Exception? = null
        var refused: Exception? = null
        val sent = ArrayList<Map<String, Any?>>()
        val guardsAtSend = ArrayList<PetFeedStatus?>()
        override fun accountId() = account
        override fun resolve(uid: String, account: Long) = PetBinding(account, "77", "me")
        override fun post(binding: PetBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit): Map<String, Any?> {
            beforeDispatch()
            if (path == PetFeedCodec.READ_PATH) return parse(read())
            assertEquals(PetFeedCodec.FEED_PATH, path)
            refused?.let { throw it }
            guardsAtSend += store.state.guards["7"]?.status
            sent += body
            afterSend?.let { throw it }
            return parse(feedAnswer())
        }
    }

    private val store = MemoryStore()
    private val pets = FakePets()
    private val free = PetFeedPlan(listOf("7"), PetFeedPolicy())

    private fun engine() = PetFeedEngine("42", store) { NOW }

    private fun tick(engine: PetFeedEngine, plan: PetFeedPlan = free, allowed: () -> Boolean = { true }): List<PetFeedOutcome> {
        val executor = PetFeedExecutor(pets)
        return engine.tick(plan, allowed, { executor.inspect(it, 42, plan.policy) }, { uid, cycle, before -> executor.feed(uid, 42, cycle, plan.policy, before) })
    }

    @Test fun `a pet whose food ran out gets one free food, saved before it is sent`() {
        val engine = engine()
        assertEquals(listOf(PetFeedStatus.CONFIRMED), tick(engine).map { it.status })
        assertEquals(listOf(PetFeedStatus.FEEDING), pets.guardsAtSend)
        assertEquals(11L, pets.sent.single()["food_id"])
        assertEquals(0L, pets.sent.single()["food_price"])
        assertEquals(PetFeedStatus.CONFIRMED, store.state.guards.getValue("7").status)
        pets.read = { response(action(NOW_S, NOW_S + 3600)) }
        assertEquals(listOf(PetFeedStatus.SKIPPED), tick(engine).map { it.status })
        assertEquals(1, pets.sent.size)
    }

    @Test fun `a feeding whose result is unknown is never sent again for the same period, even after a restart`() {
        pets.afterSend = ClaimTransportException("The host pet request timed out; the outcome is unknown")
        assertEquals(listOf(PetFeedStatus.UNKNOWN), tick(engine()).map { it.status })
        pets.afterSend = null
        assertEquals(listOf(PetFeedStatus.SKIPPED), tick(engine()).map { it.status })
        assertEquals(1, pets.sent.size)
        // A newer food of ours settles it; once that one has run out, the next feeding may go.
        pets.read = { response(action(NOW_S - 10, NOW_S + 3600)) }
        assertEquals(listOf(PetFeedStatus.CONFIRMED), tick(engine()).map { it.status })
        pets.read = { response(action(NOW_S - 10, NOW_S + 3600), serverTime = (NOW_S + 3601) * 1000) }
        pets.feedAnswer = { response(action(NOW_S + 3601, NOW_S + 7200), serverTime = (NOW_S + 3601) * 1000) }
        assertEquals(listOf(PetFeedStatus.CONFIRMED), tick(engine()).map { it.status })
        assertEquals(2, pets.sent.size)
    }

    @Test fun `a process that died while sending leaves the period uncertain`() {
        val binding = PetBinding(42, "77", "me")
        val cycle = PetFeedCodec.cycleKey(binding, EXPIRED_START * 1000, EXPIRED_END * 1000)
        store.state = PetFeedState("42", mapOf("7" to PetFeedGuard(cycle, PetFeedStatus.FEEDING, EXPIRED_END * 1000, "a", 1)))
        val engine = engine()
        assertEquals(PetFeedStatus.UNKNOWN, store.state.guards.getValue("7").status)
        tick(engine)
        assertTrue(pets.sent.isEmpty())
    }

    @Test fun `a request the host never started may be tried again`() {
        pets.refused = ClaimNotDispatchedException("The host pet request could not be started")
        assertEquals(listOf(PetFeedStatus.FAILED), tick(engine()).map { it.status })
        assertEquals(PetFeedStatus.FAILED, store.state.guards.getValue("7").status)
        pets.refused = null
        assertEquals(listOf(PetFeedStatus.CONFIRMED), tick(engine()).map { it.status })
        assertEquals(1, pets.sent.size)
    }

    @Test fun `a rejection the pet page names is final, an unknown business code is not`() {
        pets.feedAnswer = { """{"code":300002}""" }
        assertEquals(listOf(PetFeedStatus.FAILED), tick(engine()).map { it.status })
        pets.feedAnswer = { """{"code":5}""" }
        assertEquals(listOf(PetFeedStatus.UNKNOWN), tick(engine()).map { it.status })
        assertEquals(listOf(PetFeedStatus.SKIPPED), tick(engine()).map { it.status })
        assertEquals(2, pets.sent.size)
    }

    @Test fun `paid food needs a food ID, permission and a price within the limit`() {
        pets.read = { response(action(EXPIRED_START, EXPIRED_END), foods = """[{"food_id":12,"is_free":false,"price":50}]""") }
        assertEquals("NO_FREE_FOOD", tick(engine()).single().detail)
        assertEquals("PAID_NOT_ALLOWED", tick(engine(), PetFeedPlan(listOf("7"), PetFeedPolicy("12"))).single().detail)
        assertEquals("PRICE_LIMIT", tick(engine(), PetFeedPlan(listOf("7"), PetFeedPolicy("12", true, 49))).single().detail)
        assertTrue(pets.sent.isEmpty())
        pets.feedAnswer = { response(action(NOW_S, NOW_S + 3600, food = 12)) }
        assertEquals(PetFeedStatus.CONFIRMED, tick(engine(), PetFeedPlan(listOf("7"), PetFeedPolicy("12", true, 50))).single().status)
        assertEquals(50L, pets.sent.single()["food_price"])
        assertTrue(runCatching { PetFeedPolicy(allowPaidFood = true).validate() }.isFailure)
    }

    @Test fun `a stop before the request, or while its intent is saved, sends nothing`() {
        var calls = 0
        assertTrue(tick(engine()) { ++calls < 3 }.isEmpty())
        assertTrue(pets.sent.isEmpty())
        assertNull(store.state.guards["7"])
        calls = 0
        tick(engine()) { ++calls < 4 }
        assertTrue(pets.sent.isEmpty())
        assertEquals(PetFeedStatus.FAILED, store.state.guards.getValue("7").status)
    }

    @Test fun `a changed account stops the pet request`() {
        val executor = PetFeedExecutor(pets)
        pets.account = 43
        assertTrue(runCatching { executor.inspect("7", 42, PetFeedPolicy()) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { executor.inspect("42", 42, PetFeedPolicy()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `responses that do not prove our side are not ready`() {
        val binding = PetBinding(42, "77", "me")
        fun availability(json: String) = PetFeedCodec.snapshot(binding, parse(json), PetFeedPolicy()).availability
        assertEquals(PetFeedAvailability.READY, availability(response(action(EXPIRED_START, EXPIRED_END))))
        assertEquals(PetFeedAvailability.READY, availability(response(null)))
        assertEquals(PetFeedAvailability.READY, availability(response("{}")))
        assertEquals(PetFeedAvailability.STILL_FED, availability(response(action(NOW_S - 10, NOW_S + 10))))
        assertEquals(PetFeedAvailability.NO_PET, availability("""{"code":994008}"""))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response(action(EXPIRED_START, EXPIRED_END), sec = "someone")))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response(action(EXPIRED_START, EXPIRED_END), other = "me")))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response("\"bad\"")))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response(action(EXPIRED_END, EXPIRED_START))))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response(action(EXPIRED_START, EXPIRED_END), serverTime = 5)))
        assertEquals(PetFeedAvailability.UNAVAILABLE, availability(response(action(NOW_S + 60, NOW_S + 120))))
        assertEquals(PetFeedAvailability.NO_FREE_FOOD, availability(response(null, foods = """[{"food_id":11,"is_free":true,"price":0},{"food_id":11,"is_free":true,"price":0}]""")))
        assertEquals(PetFeedAvailability.NO_FREE_FOOD, availability(response(null, foods = """[{"food_id":11,"is_free":true,"price":0,"is_duoshan_exclusive":true}]""")))
        assertTrue(runCatching { availability("""{"code":7}""") }.exceptionOrNull() is PetApiException)
        assertTrue(runCatching { availability("""{"code":"x"}""") }.exceptionOrNull() is PetApiException)
    }

    @Test fun `the food name comes from its config and paid food never reads as free`() {
        val snapshot = PetFeedCodec.snapshot(PetBinding(42, "77", "me"), parse(response(null,
            foods = """[{"food_id":"12","is_free":false,"price":"30","cfg":"{\"foodName\":\"Cake\"}"}]""")), PetFeedPolicy("12", true, 30))
        assertEquals("Cake", snapshot.food!!.name)
        assertEquals(30L, snapshot.food.price)
        assertTrue(runCatching { PetFeedCodec.feedBody(snapshot.food, PetFeedPolicy("12", true, 29)) }.isFailure)
    }

    @Test fun `the cycle key names chat, account and period without collisions`() {
        val binding = PetBinding(42, "77", "me")
        val key = PetFeedCodec.cycleKey(binding, 1000, 2000)
        assertEquals(key, PetFeedCodec.cycleKey(binding, 1000, 2000))
        assertTrue(key.startsWith("v1:") && key.length == 67)
        assertNotEquals(key, PetFeedCodec.cycleKey(binding, 1000, 2001))
        assertNotEquals(key, PetFeedCodec.cycleKey(binding.copy(selfSecUid = "you"), 1000, 2000))
        assertNotEquals(key, PetFeedCodec.cycleKey(binding, null, null))
        assertNotEquals(PetFeedCodec.cycleKey(PetBinding(42, "1", "23"), null, null), PetFeedCodec.cycleKey(PetBinding(42, "12", "3"), null, null))
    }

    @Test fun `the journal survives a round trip and an unreadable one throws`() {
        val state = PetFeedState("42", mapOf("7" to PetFeedGuard("v1:x", PetFeedStatus.UNKNOWN, 5, "a", 6, null), "8" to PetFeedGuard("v1:y", PetFeedStatus.CONFIRMED, 5, "b", 6, 9)))
        assertEquals(state, PetFeedJournal.decode(PetFeedJournal.encode(state)))
        for (bad in listOf("", "{}", "[]", """{"version":2,"account":"42","guards":{}}""", """{"version":1,"account":"42","guards":{"7":{"cycle":"v1:x","status":"MAYBE","actionEnd":1,"attempt":"a","updated":1}}}""")) {
            assertTrue(bad, runCatching { PetFeedJournal.decode(bad) }.isFailure)
        }
        val directory = Files.createTempDirectory("pet").toFile()
        val file = java.io.File(directory, "a/state.dat")
        PetFeedFileStore(file, "42").write(state)
        assertEquals(state, PetFeedFileStore(file, "42").read())
        assertTrue(runCatching { PetFeedFileStore(file, "43").read() }.isFailure)
        file.writeText("{broken")
        assertTrue(runCatching { PetFeedEngine("42", PetFeedFileStore(file, "42")) { NOW } }.isFailure)
        directory.deleteRecursively()
    }

    private var account: Long? = 42L

    private fun feature(vararg saved: Pair<String, Any>, directory: java.io.File = Files.createTempDirectory("pet").toFile().apply { deleteOnExit() }) = AutoPetFeed(
        resolver(ContactQueries.SINGLE_CONVERSATION_ID to listOf(method(Chats::class.java, "conversationId", String::class.java))),
        loader, values(AutoPetFeed.ID, AutoPetFeed.OPTIONS, *saved), Logger {}, directory,
        { account ?: throw IllegalStateException("The IM SDK is not logged in") }, { _, _ -> pets }, { NOW }, { AutoCloseable {} },
    )

    @Test fun `a round feeds the chosen friends once per interval`() {
        val feature = feature(AutoPetFeed.FRIENDS to listOf("7"))
        enable(feature)
        assertEquals(15 * 60_000L, feature.round())
        assertEquals(1, pets.sent.size)
        assertEquals(15 * 60_000L, feature.round())
        assertEquals(1, pets.sent.size)
    }

    @Test fun `without a signed-in account nothing is read until it signs in`() {
        account = null
        val feature = feature(AutoPetFeed.FRIENDS to listOf("7"))
        enable(feature)
        assertEquals(15_000L, feature.round())
        assertTrue(pets.sent.isEmpty())
        account = 42
        feature.round()
        assertEquals(1, pets.sent.size)
    }

    @Test fun `paid food without a food ID keeps the feature from installing`() {
        failsToInstall(feature(AutoPetFeed.ALLOW_PAID to true), "Paid food requires")
    }

    @Test fun `a turned-off feature does not run another round`() {
        val feature = feature(AutoPetFeed.FRIENDS to listOf("7"))
        enable(feature)
        disable(feature)
        feature.round()
        assertTrue(pets.sent.isEmpty())
    }

    @Test fun `the food picker reads the foods of a friend's pet without feeding, also while the feature is off`() {
        val feature = feature()
        val foods = feature.foods("7")
        assertEquals(listOf("11" to "Bun", "12" to "12"), foods.map { it.id to it.name })
        assertEquals(listOf(true to 0L, false to 50L), foods.map { it.free to it.price })
        assertTrue(pets.sent.isEmpty())
    }

    @Test fun `the food picker says why it has nothing to show`() {
        val feature = feature()
        account = null
        assertEquals("No signed-in account", runCatching { feature.foods("7") }.exceptionOrNull()?.message)
        account = 42
        pets.read = { """{"code":994008,"server_time":$NOW}""" }
        assertTrue(runCatching { feature.foods("7") }.exceptionOrNull() is io.github.nuke.oss.app.shared.settings.PetConsoleUnavailable)
        assertTrue(runCatching { feature.foods("0") }.exceptionOrNull() is io.github.nuke.oss.app.shared.settings.PetConsoleUnavailable)
        assertTrue(runCatching { feature.foods("42") }.exceptionOrNull() is io.github.nuke.oss.app.shared.settings.PetConsoleUnavailable)
        pets.read = { throw ClaimTransportException("The pet network bridge has not started") }
        assertEquals("The pet network bridge has not started", runCatching { feature.foods("7") }.exceptionOrNull()?.message)
    }

    private companion object {
        const val NOW_S = 1_800_000_000L
        const val NOW = NOW_S * 1000
        const val EXPIRED_START = NOW_S - 7200
        const val EXPIRED_END = NOW_S - 3600
        const val FREE = """[{"food_id":11,"is_free":true,"price":0,"cfg":"{\"foodName\":\"Bun\"}"},{"food_id":12,"is_free":false,"price":50}]"""

        fun parse(text: String): Map<String, Any?> = MiniJson.obj(MiniJson.parse(text))!!

        fun action(start: Long, end: Long, food: Long = 11) = """{"food_id":$food,"start_ts":$start,"end_ts":$end}"""

        fun response(action: String?, serverTime: Long = NOW, foods: String = FREE, sec: String = "me", other: String = "friend"): String {
            val own = if (action == null) """{"sec_uid":"$sec"}""" else """{"sec_uid":"$sec","action":$action}"""
            return """{"code":0,"server_time":$serverTime,"data":{"food_info":{"list":$foods},"info":{"left":$own,"right":{"sec_uid":"$other"}}}}"""
        }
    }
}
