package io.github.nuke.oss.app.tiktok

import android.content.Context
import com.bytedance.ies.im.core.sdk.testConversationModel
import com.bytedance.im.core.model.ConversationListModel
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.TabContent
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.MessageTabMenu
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTarget
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkConversation
import io.github.nuke.oss.app.tiktok.feature.chat.claims.TabClaims
import io.github.nuke.oss.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Contract tests for the message tab menu: which tabs are marked, which long presses are taken, what the menu entry
// does with the SDK's mark-all step, and that closing the feature puts everything back. The View side is a recording
// stand-in, and a stand-in tab content plays the long-pressed View.
class MessageTabMenuTest : HookHarness() {
    private val prompts = RecordingPrompts()
    private val menu = RecordingTabMenu()
    private val logged = ArrayList<String>()
    private val queued = ArrayList<() -> Unit>()
    private val model = ConversationListModel()

    private val constructor = BottomTab::class.java.getDeclaredConstructor(Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
    private val getView = method(BottomTab::class.java, "getView")
    private val plainPress = method(TabContent::class.java, "performLongClick")
    private val pointPress = method(TabContent::class.java, "performLongClick", Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!)

    @Before fun giveAccountModel() { testConversationModel = model }

    @After fun dropAccountModel() { testConversationModel = null }

    private val spark = FakeSparkTransport()
    private val petTransport = FakePetTransport().apply { shortIds["c1"] = "101" }
    private var accountFailure: IllegalStateException? = null
    private val claims = TabClaims(
        account = { accountFailure?.let { throw it }; 7L },
        sparks = SparkClaim(spark) { listOf(SparkConversation("0:1:7:8", "555", 1)) },
        closeness = ClosenessClaim(petTransport),
        closenessTargets = { listOf(ClosenessTarget("c1")) },
    )

    private fun feature(runner: BackgroundRunner = BackgroundRunner { _, task -> task() }) =
        MessageTabMenu(loader, { menu }, claims, prompts, Logger { logged += it }, runner, TabContent::class.java)

    // Chooses the row with that label in the menu the test opened.
    private fun pick(label: Int) = menu.shown.single().single { it.label == label }.run()

    @Test fun `a message tab and its content are marked when the tab is built`() {
        enable(feature())
        val created = newTab("homepage_notification")
        assertEquals(1, menu.marked.size)
        assertSame(created, menu.marked[0].first)
        assertSame(created.getView(), menu.marked[0].second)
    }

    @Test fun `other tabs are left alone`() {
        enable(feature())
        backend.call(constructor, null, null, "homepage_hot", false)
        backend.call(getView, BottomTab(null, "homepage_friends", false))
        assertTrue(menu.marked.isEmpty())
    }

    @Test fun `a reused tab is marked when its content is asked for`() {
        enable(feature())
        val reused = BottomTab(null, "homepage_notification", false)
        backend.call(getView, reused)
        assertEquals(1, menu.marked.size)
        assertSame(reused, menu.marked[0].first)
    }

    @Test fun `a long press on the marked tab content opens the menu and is taken`() {
        enable(feature())
        val content = newTab("homepage_notification").getView()
        assertEquals(true, backend.call(plainPress, content))
        assertEquals(true, backend.call(pointPress, content, 1f, 2f))
        assertEquals(2, menu.shown.size)
    }

    @Test fun `a long press elsewhere is left to the host`() {
        enable(feature())
        newTab("homepage_notification")
        assertEquals(false, backend.call(plainPress, TabContent()))
        assertTrue(menu.shown.isEmpty())
    }

    @Test fun `a tab that is not on screen is left to the host`() {
        enable(feature())
        val content = newTab("homepage_notification").getView()
        menu.onScreen = false
        assertEquals(false, backend.call(pointPress, content, 0f, 0f))
        assertTrue(menu.shown.isEmpty())
    }

    @Test fun `choosing mark all as read submits the SDK step once and says so`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        pick(R.string.host_item_read_all)
        assertEquals(1, model.marked)
        assertEquals(listOf(R.string.host_notice_tab_read_submitted to emptyList<Any>()), prompts.toasts)
        assertTrue(logged.any { it.contains("read_all_submitted") })
    }

    @Test fun `the menu offers mark all as read, claim sparks and claim closeness in that order`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        assertEquals(listOf(R.string.host_item_read_all, R.string.host_item_claim_sparks, R.string.host_item_claim_closeness),
            menu.shown.single().map { it.label })
    }

    @Test fun `choosing claim sparks claims the earned reward and summarizes the batch`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        spark.gets += sparkList(sparkTask())
        spark.posts += 0L
        pick(R.string.host_item_claim_sparks)
        assertEquals(listOf(R.string.host_notice_spark_started, R.string.host_notice_spark_result), prompts.toasts.map { it.first })
        assertEquals(listOf<Any>(1, 1, 0, 0, 0, 0, ""), prompts.toasts[1].second)
    }

    @Test fun `choosing claim closeness claims the reward and summarizes the batch`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        petTransport.reads += pet(behavior(8))
        petTransport.claims += 0L
        pick(R.string.host_item_claim_closeness)
        assertEquals(listOf(R.string.host_notice_closeness_started, R.string.host_notice_closeness_result), prompts.toasts.map { it.first })
        assertEquals(listOf<Any>(1, 1, 0, 0, 0, 0, ""), prompts.toasts[1].second)
    }

    @Test fun `a claim that cannot start says why`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        accountFailure = IllegalStateException("The IM SDK is not logged in")
        pick(R.string.host_item_claim_closeness)
        assertEquals(R.string.host_notice_claim_failed, prompts.toasts.last().first)
        assertEquals(listOf<Any>("The IM SDK is not logged in"), prompts.toasts.last().second)
    }

    @Test fun `a claim running blocks the same claim but not the others`() {
        enable(feature(BackgroundRunner { _, task -> queued += task }))
        backend.call(plainPress, newTab("homepage_notification").getView())
        pick(R.string.host_item_claim_sparks)
        pick(R.string.host_item_claim_sparks)
        pick(R.string.host_item_claim_closeness)
        assertEquals(listOf(R.string.host_notice_spark_started, R.string.host_notice_tab_read_running, R.string.host_notice_closeness_started),
            prompts.toasts.map { it.first })
        assertEquals(2, queued.size)
    }

    @Test fun `closing the feature stops a claim before it sends anything`() {
        val feature = feature(BackgroundRunner { _, task -> queued += task })
        enable(feature)
        backend.call(plainPress, newTab("homepage_notification").getView())
        pick(R.string.host_item_claim_sparks)
        disable(feature)
        queued.single().invoke()
        assertTrue(spark.urls.isEmpty())
    }

    @Test fun `a second choice while the first is running only says it is running`() {
        enable(feature(BackgroundRunner { _, task -> queued += task }))
        backend.call(plainPress, newTab("homepage_notification").getView())
        val choose = menu.shown.single().first().run
        choose()
        choose()
        assertEquals(listOf(R.string.host_notice_tab_read_running to emptyList<Any>()), prompts.toasts)
        queued.single().invoke()
        assertEquals(1, model.marked)
        choose()
        queued[1].invoke()
        assertEquals(2, model.marked)
    }

    @Test fun `a failing SDK step is reported and does not block the next try`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        val choose = menu.shown.single().first().run
        model.failure = IllegalStateException("rejected")
        choose()
        assertEquals(listOf(R.string.host_notice_tab_read_failed to emptyList<Any>()), prompts.toasts)
        assertTrue(logged.any { it.contains("read_all_failed IllegalStateException") })
        model.failure = null
        choose()
        assertEquals(1, model.marked)
    }

    @Test fun `without a model for the account the choice fails visibly`() {
        enable(feature())
        backend.call(plainPress, newTab("homepage_notification").getView())
        testConversationModel = null
        pick(R.string.host_item_read_all)
        assertEquals(listOf(R.string.host_notice_tab_read_failed to emptyList<Any>()), prompts.toasts)
        assertEquals(0, model.marked)
    }

    @Test fun `closing the feature restores the views, stops taking presses and ignores a late choice`() {
        val feature = feature()
        enable(feature)
        val content = newTab("homepage_notification").getView()
        backend.call(plainPress, content)
        val choose = menu.shown.single().first().run
        disable(feature)
        assertEquals(1, menu.restores)
        assertEquals(false, backend.call(plainPress, content))
        assertEquals(1, menu.shown.size)
        choose()
        assertEquals(0, model.marked)
        assertFalse(prompts.toasts.any { it.first == R.string.host_notice_tab_read_submitted })
    }

    // The constructor Hook is the only way to see the tab the fake backend builds, so it hands it back through the menu.
    private fun newTab(id: String): BottomTab {
        val before = menu.marked.size
        backend.call(constructor, null, null, id, false)
        check(menu.marked.size == before + 1) { "The constructor Hook marked ${menu.marked.size - before} views" }
        return menu.marked.last().first as BottomTab
    }
}
