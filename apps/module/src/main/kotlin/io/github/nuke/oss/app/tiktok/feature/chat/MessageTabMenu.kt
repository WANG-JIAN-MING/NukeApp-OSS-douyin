package io.github.nuke.oss.app.tiktok.feature.chat

import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.reflect.declaredMethodNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.LoadedSparkConversations
import io.github.nuke.oss.app.tiktok.feature.chat.claims.PetHost
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkHost
import io.github.nuke.oss.app.tiktok.feature.chat.claims.TabClaims
import io.github.nuke.oss.app.tiktok.feature.chat.claims.closenessTargets
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

// One row of the menu: a module string ID and what choosing it does.
internal class TabEntry(val label: Int, val run: () -> Unit)

// The part of the menu that touches host Views. Everything here runs on the main thread.
internal interface TabMenu {
    // Makes the tab and its content view respond to a long press, remembering how they were.
    fun mark(tab: Any, content: Any)

    // The tab a long-pressed view stands for, or null when it is not a marked tab or is not on screen.
    fun tabOf(view: Any): Any?

    // Opens the menu next to the tab; false when it cannot be shown. A menu that is already open counts as shown.
    fun show(tab: Any, entries: List<TabEntry>): Boolean

    // Puts every marked view back as it was and closes the menu.
    fun restore()
}

// Long-pressing the message tab of the bottom bar opens a small menu. A plain tap still switches to the message page,
// because only the long press is taken. The menu has three rows:
// - Mark all as read goes through the IM SDK's own mark-all step for the current account, which covers the
//   conversations in its database and the stranger inbox; the read-report features still apply to what it sends.
// - Claim sparks and claim closeness run SparkClaim and ClosenessClaim over the friends whose streak is positive.
// Every row runs off the main thread, one run of each kind at a time, and does nothing once the feature is turned off.
@HookEntry
internal class MessageTabMenu(
    private val hostClassLoader: ClassLoader,
    private val menu: () -> TabMenu,
    private val claims: TabClaims,
    private val prompts: HostPrompts,
    private val logger: Logger,
    private val runner: BackgroundRunner = threadRunner,
    private val longPressOwner: Class<*> = View::class.java,
    // Also read by the claims' network bridges, which refuse to send once the feature is off.
    private val active: AtomicBoolean = AtomicBoolean(),
) : RuntimeFeature {
    override val id = ID
    private val reading = AtomicBoolean()
    private val sparking = AtomicBoolean()
    private val closening = AtomicBoolean()

    override fun install(scope: FeatureScope) {
        val tabType = BOTTOM_TAB.toClass(hostClassLoader)
        val constructor = tabType.resolve().optional(silent = true).constructor {
            parameters { types -> types.map(Class<*>::getName) == listOf("android.content.Context", "java.lang.String", "boolean") }
        }.singleOrNull()?.self ?: error("$BOTTOM_TAB has no (Context, String, boolean) constructor")
        val tabId = tabType.declaredMethodNamed("getCurrentTabId")
        val tabView = tabType.declaredMethodNamed("getView")
        check(tabId.parameterCount == 0 && tabId.returnType == String::class.java) { "$BOTTOM_TAB.getCurrentTabId has the wrong shape" }
        check(tabView.parameterCount == 0 && tabView.returnType != Void.TYPE) { "$BOTTOM_TAB.getView has the wrong shape" }
        val model = MODEL_HELPER.toClass(hostClassLoader).declaredMethodNamed("getConversationListModel")
        check(model.parameterCount == 0 && Modifier.isStatic(model.modifiers)) { "$MODEL_HELPER.getConversationListModel has the wrong shape" }
        val markAll = CONVERSATION_LIST.toClass(hostClassLoader).declaredMethodNamed("markAllConversationRead")
        check(markAll.parameterCount == 0 && markAll.returnType == Void.TYPE) { "$CONVERSATION_LIST.markAllConversationRead has the wrong shape" }
        // The touch timer calls the coordinate overload; the no-argument one also serves accessibility actions.
        val longPressMembers = longPressOwner.resolve()
        val longPresses = listOf(
            longPressMembers.firstMethod { name = "performLongClick"; emptyParameters() }.self,
            longPressMembers.firstMethod { name = "performLongClick"; parameters(Float::class, Float::class) }.self,
        )
        constructor.isAccessible = true
        val ui = menu()
        prompts.install(scope)
        active.set(true)
        scope.own(AutoCloseable {
            active.set(false)
            ui.restore()
        })

        fun isMessageTab(tab: Any) = tabId.invoke(tab) == MESSAGE_TAB_ID
        fun mark(tab: Any, content: Any, source: String) {
            if (!active.get() || !isMessageTab(tab)) return
            ui.mark(tab, content)
            logger.log("feature=$ID tab_marked source=$source")
        }

        // getView covers a reused tab whose content appears after the constructor ran.
        scope.hooks.install(tabView, HookSpec(id = "TabView", after = { call ->
            val content = (call.outcome as? CallOutcome.Returned)?.value
            val tab = call.receiver
            if (tab != null && content != null) mark(tab, content, "getView")
        }))
        scope.hooks.install(constructor, HookSpec(
            id = "TabConstructor",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call ->
                val tab = call.receiver
                if (call.outcome is CallOutcome.Returned && tab != null && active.get() && isMessageTab(tab)) {
                    mark(tab, checkNotNull(tabView.invoke(tab)) { "The message tab has no content view" }, "constructor")
                }
            },
        ))
        longPresses.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = "TabLongPress$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    val tab = call.receiver?.let(ui::tabOf)
                    if (active.get() && tab != null && isMessageTab(tab) && ui.show(tab, entries(model, markAll))) call.returnEarly(true)
                },
            ))
        }
    }

    private fun entries(model: java.lang.reflect.Method, markAll: java.lang.reflect.Method) = listOf(
        TabEntry(R.string.host_item_read_all) { readAll(model, markAll) },
        TabEntry(R.string.host_item_claim_sparks) { claimSparks() },
        TabEntry(R.string.host_item_claim_closeness) { claimCloseness() },
    )

    private fun readAll(model: java.lang.reflect.Method, markAll: java.lang.reflect.Method) {
        if (!reading.compareAndSet(false, true)) return prompts.toast(R.string.host_notice_tab_read_running)
        runner.run(0) {
            try {
                if (active.get()) {
                    val target = checkNotNull(model.invoke(null)) { "No conversation list model for the current account" }
                    check(markAll.declaringClass.isInstance(target)) { "Unsupported conversation list model ${target.javaClass.name}" }
                    markAll.invoke(target)
                    logger.log("feature=$ID read_all_submitted")
                    prompts.toast(R.string.host_notice_tab_read_submitted)
                }
            } catch (failure: ReflectiveOperationException) {
                logger.log("feature=$ID read_all_failed ${(failure.cause ?: failure).javaClass.simpleName}")
                prompts.toast(R.string.host_notice_tab_read_failed)
            } catch (failure: IllegalStateException) {
                logger.log("feature=$ID read_all_failed ${failure.message}")
                prompts.toast(R.string.host_notice_tab_read_failed)
            } finally {
                reading.set(false)
            }
        }
    }

    private fun claimSparks() = batch(sparking, "sparks", R.string.host_notice_spark_started) { account ->
        val report = claims.sparks.claimAll(account, active::get)
        logger.log("feature=$ID sparks scanned=${report.scanned} claimed=${report.claimed} already=${report.already} " +
            "failed=${report.failed} unknown=${report.unknown} unavailable=${report.unavailable}")
        if (report.scanned == 0 && report.details.isEmpty()) prompts.toast(R.string.host_notice_claim_none)
        else prompts.toast(R.string.host_notice_spark_result, report.scanned, report.claimed, report.already, report.failed,
            report.unknown, report.unavailable, detail(report.details))
    }

    private fun claimCloseness() = batch(closening, "closeness", R.string.host_notice_closeness_started) { account ->
        val report = claims.closeness.claimAll(account, claims.closenessTargets(account), active::get)
        logger.log("feature=$ID closeness scanned=${report.scanned} claimed=${report.claimed} already=${report.already} " +
            "failed=${report.failed} unknown=${report.unknown} unavailable=${report.unavailable}")
        if (report.scanned == 0 && report.details.isEmpty()) prompts.toast(R.string.host_notice_claim_none)
        else prompts.toast(R.string.host_notice_closeness_result, report.scanned, report.claimed, report.already, report.failed,
            report.unknown, report.unavailable, detail(report.details))
    }

    // One batch of a kind at a time. A lookup that could not be made reaches the user as the failure reason; the batch
    // itself reports its own per-friend problems in its counts and details.
    private fun batch(flag: AtomicBoolean, name: String, started: Int, run: (account: Long) -> Unit) {
        if (!flag.compareAndSet(false, true)) return prompts.toast(R.string.host_notice_tab_read_running)
        prompts.toast(started)
        runner.run(0) {
            try {
                if (active.get()) run(claims.account())
            } catch (failure: Exception) {
                if (failure !is IllegalStateException && failure !is ReflectiveOperationException && failure !is HostContactsUnavailable) throw failure
                val reason = (failure.message ?: (failure.cause ?: failure).javaClass.simpleName).take(REASON_LENGTH)
                logger.log("feature=$ID ${name}_failed ${failure.javaClass.simpleName}")
                prompts.toast(R.string.host_notice_claim_failed, reason)
            } finally {
                flag.set(false)
            }
        }
    }

    private fun detail(details: List<String>) = details.firstOrNull()?.let { "\n$it" }.orEmpty()

    companion object : TikTokFeature {
        const val ID = "MessageTabMenu"
        const val BOTTOM_TAB = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab"
        const val MODEL_HELPER = "com.bytedance.ies.im.core.sdk.MultiInstanceHelperKt"
        const val CONVERSATION_LIST = "com.bytedance.im.core.model.ConversationListModel"
        const val MESSAGE_TAB_ID = "homepage_notification"
        private const val REASON_LENGTH = 200

        // The claims' host access is built on first use: the features are created before the Dex queries are located.
        override fun create(services: TikTokFeatureServices) = with(services) {
            val active = AtomicBoolean()
            val account = ImAccount(hostClassLoader)
            val models = ConversationModels(hostClassLoader)
            val contacts = HostContacts { DouyinContacts.open(resolver, hostClassLoader, logger, ID).load() }
            val claims = TabClaims(
                account = account::id,
                sparks = SparkClaim(SparkHost(hostClassLoader, account, active::get), LoadedSparkConversations(contacts, models, account, hostClassLoader)),
                closeness = ClosenessClaim(PetHost(hostClassLoader, account, models, active::get, logger)),
                closenessTargets = { id -> closenessTargets(contacts, id) },
            )
            listOf(MessageTabMenu(hostClassLoader, tabMenu, claims, prompts(ID), logger, active = active))
        }
    }
}
