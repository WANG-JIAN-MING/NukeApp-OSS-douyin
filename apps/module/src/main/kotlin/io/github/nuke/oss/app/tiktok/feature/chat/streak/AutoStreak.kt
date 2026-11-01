package io.github.nuke.oss.app.tiktok.feature.chat.streak

import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ActivityRecord
import io.github.nuke.oss.app.shared.settings.ActivityStatus
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.FriendsOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.comment.CustomCommentStyle
import io.github.nuke.oss.app.tiktok.query.StreakQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatSender
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId

// Keeps chat streaks going: once a day, from the chosen time, sends one message to each chosen friend through the
// host's own chat sender. The schedule and its guarantees (at most one send per friend and local day, a claim on disk
// before every send, no retry of an unknown result) live in StreakEngine; this class only connects it to the host.
// A worker thread sleeps until the next send time, in steps of at most a minute so a changed clock is noticed, and
// stops with the feature. A host process the system has frozen sends nothing until it runs again, and then only
// catches up the current day. Logs name the feature and the outcome, never a friend, an account or message text.
@HookEntry
internal class AutoStreak(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val options: OptionValues,
    private val logger: Logger,
    private val journalDirectory: File,
    private val accountId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> String = { ZoneId.systemDefault().id },
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    // The sender's queries, and the interactive emoji's only in that mode.
    override val dexQueries: List<DexQuery> = SENDER_QUERIES +
        (if (options.choice(MODE).equals("interactive", ignoreCase = true)) EMOJI_QUERIES else emptyList())
    @Volatile private var active = false

    override fun install(scope: FeatureScope) {
        val mode = StreakMode.valueOf(options.choice(MODE).uppercase())
        val plan = StreakPlan(
            friends = options.friends(FRIENDS).mapNotNull(StreakPlan::canonical).toSet(),
            mode = mode,
            text = options.text(TEXT),
            hitokotoUrl = options.text(HITOKOTO_URL),
            hour = options.number(HOUR).toInt(),
            minute = options.number(MINUTE).toInt(),
            zoneId = zone(),
        )
        // A plan that does not validate fails the install, so the settings screen shows why nothing will be sent.
        plan.validate()
        val (create, content, user, send, sent, failed, text) = resolver.methods(hostClassLoader, SENDER_QUERIES)
        val sender = ChatSender(hostClassLoader, create, content, user, send, sent, failed, text)
        val emoji = if (mode == StreakMode.INTERACTIVE) {
            val (entry, configuration, convert, resources, owner, emojiContent) = resolver.methods(hostClassLoader, EMOJI_QUERIES)
            StreakEmoji(entry, configuration, convert, resources, owner, emojiContent)
        } else null
        active = true
        val thread = Thread { run(plan, sender, emoji) }.apply { isDaemon = true; name = "nuke-streak" }
        scope.own(AutoCloseable {
            active = false
            thread.interrupt()
        })
        thread.start()
    }

    private fun run(plan: StreakPlan, sender: ChatSender, emoji: StreakEmoji?) {
        val engines = HashMap<String, StreakEngine>()
        try {
            while (active) {
                val account = accountId()?.let(StreakPlan::canonical)
                if (account == null) {
                    sleeper(ACCOUNT_RETRY_MILLIS)
                    continue
                }
                try {
                    val engine = engines.getOrPut(account) { StreakEngine(account, StreakFileStore(journalFile(journalDirectory, account), account)) }
                    val now = clock()
                    engine.adopt(plan, now)
                    var sentOne = false
                    engine.tick(
                        plan, now,
                        allowed = { active && accountId()?.let(StreakPlan::canonical) == account },
                        clock = clock,
                        prepare = { StreakMessages.prepare(it, HitokotoClient::fetch) { check(emoji != null) { "No interactive emoji provider" }; emoji.create() } },
                        send = { uid, message, canContinue ->
                            if (sentOne) sleeper(SEND_GAP_MILLIS)
                            sentOne = true
                            deliver(sender, emoji, uid, message, canContinue)
                        },
                    )
                    sleeper((engine.nextWakeAt(plan, clock()) - clock()).coerceIn(MIN_SLEEP_MILLIS, MAX_SLEEP_MILLIS))
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: RuntimeException) {
                    // A broken journal or a changed host is reported once per round; no send happens without a claim.
                    logger.log("feature=$ID round_failed ${error.javaClass.simpleName}")
                    sleeper(FAILURE_RETRY_MILLIS)
                }
            }
        } catch (_: InterruptedException) {
            // The feature was turned off.
        }
    }

    private fun deliver(sender: ChatSender, emoji: StreakEmoji?, uid: String, message: String, canContinue: () -> Boolean): StreakOutcome {
        val content = if (emoji != null) emoji.create() else sender.text(message)
        if (!canContinue()) return StreakOutcome(StreakStatus.UNKNOWN, "Stopped before the send")
        return when (val result = sender.send(uid, content, SEND_TIMEOUT_MILLIS)) {
            is SendResult.Sent -> StreakOutcome(StreakStatus.ACKNOWLEDGED, result.detail, result.clientMessageId, result.serverMessageId)
            is SendResult.Failed -> StreakOutcome(StreakStatus.FAILED, result.detail)
            is SendResult.Unknown -> StreakOutcome(StreakStatus.UNKNOWN, result.detail)
        }.also { logger.log("feature=$ID send_${it.status.name.lowercase()}") }
    }

    companion object : TikTokFeature {
        const val ID = "AutoStreak"
        private val SENDER_QUERIES = listOf(
            StreakQueries.CREATE_SENDER, StreakQueries.SET_CONTENT, StreakQueries.SET_USER,
            StreakQueries.SEND, StreakQueries.SENT, StreakQueries.SEND_FAILED, StreakQueries.TEXT_CONTENT,
        )
        private val EMOJI_QUERIES = listOf(
            StreakQueries.INTERACTION_ENTRY, StreakQueries.INTERACTION_CONFIGURATION, StreakQueries.RESOURCE_TO_EMOJI,
            StreakQueries.INTERACTIVE_RESOURCES, StreakQueries.RESOURCE_LOADER_OWNER, StreakQueries.EMOJI_CONTENT,
        )
        const val FRIENDS = "friends"
        const val MODE = "mode"
        const val TEXT = "text"
        const val HITOKOTO_URL = "hitokoto-url"
        const val HOUR = "hour"
        const val MINUTE = "minute"
        val MODES = listOf("text", "hitokoto", "interactive")
        val OPTIONS = listOf(
            FriendsOption(FRIENDS),
            ChoiceOption(MODE, MODES),
            TextOption(TEXT, default = "[玫瑰]", maxLength = StreakPlan.MAX_TEXT),
            TextOption(HITOKOTO_URL, default = HitokotoClient.DEFAULT_URL, maxLength = 300),
            NumberOption(HOUR, default = 19, min = 0, max = 23),
            NumberOption(MINUTE, default = 0, min = 0, max = 59),
        )
        private const val ACCOUNT_RETRY_MILLIS = 15_000L
        private const val FAILURE_RETRY_MILLIS = 60_000L
        private const val MIN_SLEEP_MILLIS = 1_000L
        private const val MAX_SLEEP_MILLIS = 60_000L
        private const val SEND_GAP_MILLIS = 1_000L
        private const val SEND_TIMEOUT_MILLIS = 30_000L

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AutoStreak(resolver, hostClassLoader, options(ID, OPTIONS), logger, streakDirectory, CustomCommentStyle.accountUserId(hostClassLoader, logger)))
        }

        // The sends of the signed-in account, newest first, for the settings screen; empty without an account or before
        // the first send. The worker replaces the journal atomically, so a read sees one whole version of it. A journal
        // that does not read throws IllegalStateException with the reason.
        fun history(journalDirectory: File, accountId: String?): List<ActivityRecord> {
            val account = accountId?.let(StreakPlan::canonical) ?: return emptyList()
            val state = try {
                StreakFileStore(journalFile(journalDirectory, account), account).read()
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("The streak journal does not read: ${error.message}", error)
            } catch (error: java.io.IOException) {
                throw IllegalStateException("The streak journal does not read: ${error.javaClass.simpleName}", error)
            }
            return state.history.map { attempt ->
                val status = when (attempt.status) {
                    StreakStatus.CLAIMED -> ActivityStatus.PENDING
                    StreakStatus.ACKNOWLEDGED -> ActivityStatus.DONE
                    StreakStatus.FAILED -> ActivityStatus.FAILED
                    StreakStatus.UNKNOWN -> ActivityStatus.UNKNOWN
                }
                ActivityRecord(attempt.startedAt, attempt.uid, status, attempt.mode.name.lowercase(), attempt.usedFallback, attempt.detail)
            }
        }

        // The account only names a directory as a hash, so the stored path carries no user ID.
        private fun journalFile(directory: File, account: String): File {
            val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(directory, "$hash/state.dat")
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
private operator fun <T> List<T>.component7(): T = this[6]
