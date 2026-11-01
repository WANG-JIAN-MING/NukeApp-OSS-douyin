package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessBinding
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessCodec
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTarget
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTransport
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkCodec
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkConversation
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkTransport
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun sparkTask(id: Any = 11L, token: String = "t1", status: Long = 2, reward: String = "spark", interaction: Long = 4, unique: Any? = null, children: List<Any?>? = null): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "task_status" to status, "reward" to mapOf("reward_type" to reward),
        "button_config" to mapOf("interaction" to mapOf("type" to interaction)), "task_id" to id, "token" to token,
    ).apply {
        if (unique != null) put("unique_task_id", unique)
        if (children != null) put("sub_tasks", children)
    }

internal fun sparkList(vararg nodes: Map<String, Any?>): Map<String, Any?> = mapOf("status_code" to 0L, "new_task_list" to nodes.toList())

// Answers the spark transport from a script: each GET pops the next task list, each POST the next status or failure.
internal class FakeSparkTransport(var account: Long = 7) : SparkTransport {
    val gets = ArrayDeque<Map<String, Any?>>()
    val posts = ArrayDeque<Any>()
    val urls = ArrayList<String>()
    val bodies = ArrayList<Map<String, Any?>>()
    var failGet: IOException? = null
    override fun accountId() = account
    override fun request(url: String, body: Map<String, Any?>?, beforeDispatch: () -> Unit): Map<String, Any?> {
        urls += url
        if (body == null) {
            failGet?.let { throw it }
            return gets.removeFirst()
        }
        bodies += body
        beforeDispatch()
        return when (val next = posts.removeFirst()) {
            is IOException -> throw next
            is Long -> mapOf("status_code" to next)
            else -> error("Unsupported scripted answer")
        }
    }
}

class SparkClaimTest {
    private val transport = FakeSparkTransport()
    private val chat = SparkConversation("0:1:7:8", "555", 1)
    private fun claim(vararg chats: SparkConversation = arrayOf(chat)) = SparkClaim(transport) { chats.toList() }

    @Test fun `an earned spark reward is read then claimed with the server's own IDs`() {
        transport.gets += sparkList(sparkTask(id = 9007199254740993L, token = "tok"))
        transport.posts += 0L
        val report = claim().claimAll(7) { true }
        assertEquals(1, report.scanned)
        assertEquals(1, report.claimed)
        assertTrue(transport.urls[0].endsWith("conversation_short_id=555&need_pet_elf=true"))
        val body = transport.bodies.single()
        assertEquals(9007199254740993L, body["task_id"])
        assertEquals("tok", body["token"])
        assertEquals("pet", body["enter_from"])
        assertEquals(mapOf("conversation_type" to 1L, "conversation_id" to "0:1:7:8", "conversation_short_id" to "555"), body["conversation_info"])
    }

    @Test fun `only earned spark rewards with the claim button are candidates and a claimable parent hides its children`() {
        val child = sparkTask(id = 3L, token = "c")
        val tasks = SparkCodec.tasks(sparkList(
            sparkTask(id = 1L, status = 1),
            sparkTask(id = 2L, reward = "diamond"),
            sparkTask(id = 4L, interaction = 3),
            sparkTask(id = 5L, token = ""),
            sparkTask(id = 6L, status = 1, children = listOf(child)),
            sparkTask(id = 7L, children = listOf(sparkTask(id = 8L))),
        ))
        assertEquals(listOf("3", "7"), tasks.map { it.key })
    }

    @Test fun `a task with an empty or zero unique ID is keyed by its task ID`() {
        val tasks = SparkCodec.tasks(sparkList(sparkTask(id = 5L, unique = ""), sparkTask(id = 6L, unique = 0L), sparkTask(id = 7L, unique = "u7")))
        assertEquals(listOf("5", "6", "u7"), tasks.map { it.key })
    }

    @Test fun `a later candidate is read again and the fresh token is the one sent`() {
        transport.gets += sparkList(sparkTask(id = 1L, token = "a"), sparkTask(id = 2L, token = "old"))
        transport.gets += sparkList(sparkTask(id = 2L, token = "fresh"))
        transport.posts += 0L
        transport.posts += 0L
        val report = claim().claimAll(7) { true }
        assertEquals(2, report.claimed)
        assertEquals(listOf("a", "fresh"), transport.bodies.map { it["token"] })
    }

    @Test fun `a candidate that is gone after the re-read is skipped`() {
        transport.gets += sparkList(sparkTask(id = 1L), sparkTask(id = 2L))
        transport.gets += sparkList()
        transport.posts += 0L
        val report = claim().claimAll(7) { true }
        assertEquals(1, report.claimed)
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `already claimed and rejected rewards are counted apart`() {
        transport.gets += sparkList(sparkTask(id = 1L), sparkTask(id = 2L))
        transport.gets += sparkList(sparkTask(id = 2L))
        transport.posts += 996004L
        transport.posts += 996003L
        val report = claim().claimAll(7) { true }
        assertEquals(1, report.already)
        assertEquals(1, report.failed)
        assertEquals(0, report.claimed)
        assertTrue(report.details.single().contains("996003"))
    }

    @Test fun `a request that was dispatched and then failed is unknown, not failed`() {
        transport.gets += sparkList(sparkTask())
        transport.posts += IOException("timed out")
        val report = claim().claimAll(7) { true }
        assertEquals(1, report.unknown)
        assertEquals(0, report.failed)
    }

    @Test fun `an unreadable reward list marks the chat unavailable`() {
        transport.failGet = IOException("offline")
        val report = claim().claimAll(7) { true }
        assertEquals(1, report.scanned)
        assertEquals(1, report.unavailable)
        assertTrue(transport.bodies.isEmpty())
    }

    @Test fun `a stopped feature sends nothing and says why`() {
        val report = claim().claimAll(7) { false }
        assertTrue(transport.urls.isEmpty())
        assertTrue(report.details.single().contains("stopped"))
    }

    @Test fun `a changed account ends the batch before the next request`() {
        transport.gets += sparkList(sparkTask())
        transport.posts += 0L
        var reads = 0
        val account = object : SparkTransport by transport {
            override fun accountId() = if (reads++ < 6) 7L else 8L
        }
        val report = SparkClaim(account) { listOf(chat, SparkConversation("0:1:7:9", "556", 1)) }.claimAll(7) { true }
        assertTrue(report.details.first().contains("Account changed"))
        assertTrue(transport.bodies.size <= 1)
    }

    @Test fun `a lookup failure reaches the caller`() {
        val failing = SparkClaim(transport) { throw IllegalStateException("no friends") }
        val failure = runCatching { failing.claimAll(7) { true } }.exceptionOrNull()
        assertEquals("no friends", failure?.message)
    }

    @Test fun `the same chat listed twice is queried once`() {
        transport.gets += sparkList()
        val report = claim(chat, SparkConversation("0:1:7:8", "555", 1)).claimAll(7) { true }
        assertEquals(1, report.scanned)
    }
}

internal fun pet(vararg behaviors: Map<String, Any?>, code: Long = 0): Map<String, Any?> =
    mapOf("code" to code, "data" to mapOf("closeness" to mapOf("behavior_closeness" to behaviors.toList())))

internal fun behavior(id: Long, claimable: Any? = true, ts: Long? = 100) =
    linkedMapOf<String, Any?>("behavior_id" to id, "is_claimable" to claimable).apply { if (ts != null) put("reward_ts", ts) }

internal class FakePetTransport(var account: Long = 7) : ClosenessTransport {
    val reads = ArrayDeque<Map<String, Any?>>()
    val claims = ArrayDeque<Any>()
    val sent = ArrayList<Pair<String, Map<String, Any?>>>()
    val shortIds = HashMap<String, String>()
    var resolveFailure: Exception? = null
    override fun accountId() = account
    override fun resolve(conversationId: String, account: Long): ClosenessBinding {
        resolveFailure?.let { throw it }
        return ClosenessBinding(account, shortIds[conversationId] ?: error("No such chat"))
    }
    override fun post(binding: ClosenessBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit): Map<String, Any?> {
        sent += path to body
        beforeDispatch()
        if (path == ClosenessCodec.READ_PATH) return reads.removeFirst()
        return when (val next = claims.removeFirst()) {
            is IOException -> throw next
            is Long -> mapOf("code" to next)
            else -> error("Unsupported scripted answer")
        }
    }
}

class ClosenessClaimTest {
    private val transport = FakePetTransport().apply { shortIds["c1"] = "101"; shortIds["c2"] = "102"; shortIds["alias"] = "101" }
    private val claim = ClosenessClaim(transport)
    private fun targets(vararg ids: String) = ids.map(::ClosenessTarget)

    @Test fun `only strictly claimable rewards with a valid ID are read, newest first`() {
        val rewards = ClosenessCodec.rewards(pet(behavior(1, ts = 10), behavior(2, claimable = "true"), behavior(3, claimable = false), behavior(4, ts = 30), behavior(1, ts = 10)))
        assertEquals(listOf(4L, 1L), rewards.map { it.behaviorId })
    }

    @Test fun `no pet or closeness switched off means nothing to claim`() {
        assertTrue(ClosenessCodec.rewards(mapOf("code" to 994008L)).isEmpty())
        assertTrue(ClosenessCodec.rewards(mapOf("code" to 996506L)).isEmpty())
        assertTrue(runCatching { ClosenessCodec.rewards(mapOf("code" to 5L)) }.isFailure)
    }

    @Test fun `a claimable reward with no valid ID or conflicting copies is an error`() {
        assertTrue(runCatching { ClosenessCodec.rewards(pet(behavior(0))) }.isFailure)
        assertTrue(runCatching { ClosenessCodec.rewards(pet(behavior(1, ts = 1), behavior(1, ts = 2))) }.isFailure)
    }

    @Test fun `a claimable reward is read then claimed by its behavior ID only`() {
        transport.reads += pet(behavior(8))
        transport.claims += 0L
        val report = claim.claimAll(7, targets("c1")) { true }
        assertEquals(1, report.scanned)
        assertEquals(1, report.claimed)
        assertEquals(listOf(ClosenessCodec.READ_PATH, ClosenessCodec.CLAIM_PATH), transport.sent.map { it.first })
        assertEquals(mapOf("source" to "Home"), transport.sent[0].second)
        assertEquals(mapOf("behavior_id" to 8L), transport.sent[1].second)
    }

    @Test fun `a second reward is re-read and only claimed while its timestamp is unchanged`() {
        transport.reads += pet(behavior(8, ts = 200), behavior(9, ts = 100))
        transport.reads += pet(behavior(9, ts = 150))
        transport.claims += 0L
        val report = claim.claimAll(7, targets("c1")) { true }
        assertEquals(1, report.claimed)
        assertEquals(1, transport.sent.count { it.first == ClosenessCodec.CLAIM_PATH })
    }

    @Test fun `already claimed and rejected claims are counted apart`() {
        transport.reads += pet(behavior(8, ts = 200), behavior(9, ts = 100))
        transport.reads += pet(behavior(9, ts = 100))
        transport.claims += 996502L
        transport.claims += 5L
        val report = claim.claimAll(7, targets("c1"))  { true }
        assertEquals(1, report.already)
        assertEquals(1, report.failed)
    }

    @Test fun `an unconfirmed write ends the whole batch`() {
        transport.reads += pet(behavior(8))
        transport.claims += IOException("timed out")
        val report = claim.claimAll(7, targets("c1", "c2")) { true }
        assertEquals(1, report.unknown)
        assertEquals(1, report.scanned)
        assertEquals(2, transport.sent.size)
    }

    @Test fun `friends that share a conversation are read once and a failed lookup is unavailable`() {
        transport.reads += pet()
        val report = claim.claimAll(7, targets("c1", "alias")) { true }
        assertEquals(1, report.scanned)
        transport.resolveFailure = IllegalStateException("not found")
        val failed = claim.claimAll(7, targets("c2")) { true }
        assertEquals(1, failed.unavailable)
        assertTrue(failed.details.single().contains("not found"))
    }

    @Test fun `a stopped feature or another account sends nothing`() {
        assertTrue(claim.claimAll(7, targets("c1")) { false }.details.single().contains("stopped"))
        transport.account = 9
        assertTrue(claim.claimAll(7, targets("c1")) { true }.details.single().contains("Account changed"))
        assertTrue(transport.sent.isEmpty())
    }
}
