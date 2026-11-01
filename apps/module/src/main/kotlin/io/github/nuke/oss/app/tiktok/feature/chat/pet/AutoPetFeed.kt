package io.github.nuke.oss.app.tiktok.feature.chat.pet

import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.PetConsoleUnavailable
import io.github.nuke.oss.app.shared.settings.PetFoodChoice
import io.github.nuke.oss.app.shared.settings.FriendsOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessBinding
import io.github.nuke.oss.app.tiktok.feature.chat.claims.PetHost
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Feeds the pets you share with the chosen friends, as the legacy module's AutoPetFeed does. A worker thread checks
// every friend's pet once per interval through the pet page's own requests, and when your food has run out gives a
// free one, or the chosen food. Paid food is never given unless a food ID is set, paid food is allowed and its
// price is within the limit. Every feeding is first written to the account's journal (PetFeedEngine), so a feeding
// whose result is unknown is never sent again for the same period. Nothing runs while the host process is not
// running, and nothing is fed before the host's pet network bridge has started, which opening a native pet page
// does. Logs carry counts and statuses, never a friend, an account or a food.
@HookEntry
internal class AutoPetFeed(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val options: OptionValues,
    private val logger: Logger,
    private val journalDirectory: File,
    private val accountId: () -> Long,
    private val transport: (conversationId: Method, active: () -> Boolean) -> PetFeedTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val startWorker: (task: () -> Unit) -> AutoCloseable = ::daemon,
) : RuntimeFeature, UsesDexQueries, PetConsole {
    override val id = ID
    override val dexQueries = listOf(ContactQueries.SINGLE_CONVERSATION_ID)
    @Volatile private var active = false
    private var plan: PetFeedPlan? = null
    private var intervalMillis = 0L
    private var pets: PetFeedTransport? = null
    // The transport the settings screen reads foods through while the feature is not running. A reading creates no
    // journal and feeds nothing.
    private val browsing by lazy {
        val conversationId = resolver.methods(hostClassLoader, ContactQueries.SINGLE_CONVERSATION_ID).single().apply { isAccessible = true }
        transport(conversationId) { true }
    }
    private val engines = HashMap<String, PetFeedEngine>()
    private val nextCheckAt = HashMap<String, Long>()

    override fun install(scope: FeatureScope) {
        val policy = PetFeedPolicy(options.text(FOOD_ID).ifBlank { null }, options.toggle(ALLOW_PAID), options.number(MAX_PRICE))
        val chosen = PetFeedPlan(options.friends(FRIENDS).distinct(), policy)
        // A plan that does not validate, such as paid food without a food ID, fails the install so the reason shows.
        chosen.validate()
        val conversationId = resolver.methods(hostClassLoader, ContactQueries.SINGLE_CONVERSATION_ID).single().apply { isAccessible = true }
        plan = chosen
        intervalMillis = options.number(INTERVAL_MINUTES) * 60_000L
        active = true
        pets = transport(conversationId) { active }
        val worker = startWorker(::run)
        scope.own(AutoCloseable {
            active = false
            worker.close()
        })
    }

    // The foods a friend's pet offers, for the food picker. Reads with the feature's own transport when it runs.
    override fun foods(friendUid: String): List<PetFoodChoice> {
        val account = try {
            accountId()
        } catch (error: IllegalStateException) {
            throw PetConsoleUnavailable("No signed-in account", error)
        }
        try {
            return PetFeedExecutor(pets ?: browsing).catalog(friendUid, account).map { PetFoodChoice(it.id, it.name, it.price, it.isFree) }
        } catch (error: IllegalStateException) {
            throw PetConsoleUnavailable(error.message ?: error.javaClass.simpleName, error)
        } catch (error: IllegalArgumentException) {
            throw PetConsoleUnavailable(error.message ?: error.javaClass.simpleName, error)
        } catch (error: java.io.IOException) {
            throw PetConsoleUnavailable(error.message ?: error.javaClass.simpleName, error)
        }
    }

    private fun run() {
        try {
            Thread.sleep(START_DELAY_MILLIS)
            while (active) Thread.sleep(round().coerceIn(MIN_SLEEP_MILLIS, MAX_SLEEP_MILLIS))
        } catch (_: InterruptedException) {
            // The feature was turned off.
        }
    }

    // One pass of the worker: checks the friends if their interval is due and answers how long to wait.
    internal fun round(): Long {
        val plan = plan ?: return MAX_SLEEP_MILLIS
        val pets = pets ?: return MAX_SLEEP_MILLIS
        if (!active || plan.friends.isEmpty()) return MAX_SLEEP_MILLIS
        val account = try {
            accountId()
        } catch (_: IllegalStateException) {
            return ACCOUNT_RETRY_MILLIS
        }
        val key = account.toString()
        val now = clock()
        nextCheckAt[key]?.let { if (now < it) return it - now }
        nextCheckAt[key] = now + intervalMillis
        try {
            val engine = engines.getOrPut(key) { PetFeedEngine(key, PetFeedFileStore(journalFile(key), key), clock) }
            val executor = PetFeedExecutor(pets)
            val outcomes = engine.tick(
                plan,
                canContinue = { active && sameAccount(account) },
                inspect = { uid -> executor.inspect(uid, account, plan.policy) },
                feed = { uid, cycle, beforeSubmit -> executor.feed(uid, account, cycle, plan.policy, beforeSubmit) },
            )
            val counts = outcomes.groupingBy { it.status.name.lowercase() }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" }
            logger.log("feature=$ID round ${counts.ifEmpty { "stopped" }}")
        } catch (error: RuntimeException) {
            // A broken journal or a changed host stops this round; nothing is sent without a saved intent.
            logger.log("feature=$ID round_failed ${error.javaClass.simpleName}")
        }
        return intervalMillis
    }

    private fun sameAccount(account: Long) = try {
        accountId() == account
    } catch (_: IllegalStateException) {
        false
    }

    // The account only names a directory as a hash, so the stored path carries no user ID.
    private fun journalFile(account: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(journalDirectory, "$hash/state.dat")
    }

    companion object : TikTokFeature {
        const val ID = "AutoPetFeed"
        const val FRIENDS = "friends"
        const val INTERVAL_MINUTES = "interval-minutes"
        const val FOOD_ID = "food-id"
        const val ALLOW_PAID = "allow-paid"
        const val MAX_PRICE = "max-price"
        val OPTIONS = listOf(
            FriendsOption(FRIENDS),
            NumberOption(INTERVAL_MINUTES, default = 15, min = 5, max = 60),
            TextOption(FOOD_ID, maxLength = 19),
            ToggleOption(ALLOW_PAID),
            NumberOption(MAX_PRICE, default = 0, min = 0, max = 1_000_000),
        )
        private const val START_DELAY_MILLIS = 1_000L
        private const val ACCOUNT_RETRY_MILLIS = 15_000L
        private const val MIN_SLEEP_MILLIS = 1_000L
        private const val MAX_SLEEP_MILLIS = 30_000L
        private const val SEC_UID_TIMEOUT_MILLIS = 5_000L

        private fun daemon(task: () -> Unit): AutoCloseable {
            val thread = Thread(task).apply { isDaemon = true; name = "nuke-pet-feed" }
            thread.start()
            return AutoCloseable { thread.interrupt() }
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            val account = ImAccount(hostClassLoader)
            val reader = DouyinAccountReader(DouyinAccountReader.service(hostClassLoader)) { logger.log("feature=$ID account_unavailable ${it.javaClass.simpleName}") }
            // The account service is read on the main thread; the worker waits for the answer.
            val secUid = { expected: Long ->
                val answer = ArrayBlockingQueue<Result<String?>>(1)
                scheduler.schedule(0) { answer.offer(runCatching { reader.secUid(expected.toString()) }) }
                answer.poll(SEC_UID_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)?.getOrThrow()
            }
            // The pet journals sit beside the streak journals in the host's no_backup directory.
            listOf(AutoPetFeed(resolver, hostClassLoader, options(ID, OPTIONS), logger, streakDirectory.resolveSibling("pet"), account::id, { conversationId, active ->
                HostPetFeedTransport(PetHost(hostClassLoader, account, ConversationModels(hostClassLoader), active, logger), conversationId, secUid)
            }))
        }
    }
}

// The pet requests through the host: a friend's one-to-one chat from the host's conversation ID helper and the pet
// page's own Retrofit calls (PetHost), with the account's secure UID read once per account.
internal class HostPetFeedTransport(
    private val host: PetHost,
    private val conversationId: Method,
    private val secUid: (account: Long) -> String?,
) : PetFeedTransport {
    private val secUids = ConcurrentHashMap<Long, String>()

    override fun accountId() = host.accountId()

    override fun resolve(uid: String, account: Long): PetBinding {
        val chat = try {
            conversationId.invoke(null, uid) as? String
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("The host has no conversation ID for the friend", error.targetException)
        }?.takeIf { it.isNotBlank() } ?: error("The host has no conversation ID for the friend")
        val binding = host.resolve(chat, account)
        val sec = secUids[account] ?: secUid(account)?.also { secUids[account] = it } ?: error("The account's secure UID is unavailable")
        return PetBinding(account, binding.shortId, sec)
    }

    override fun post(binding: PetBinding, path: String, body: Map<String, Any?>, beforeDispatch: () -> Unit) =
        host.post(ClosenessBinding(binding.account, binding.shortId), path, body, beforeDispatch)
}
