package io.github.nuke.oss.app.tiktok

import androidx.recyclerview.widget.RecyclerView
import com.ss.android.ugc.aweme.sharer.ui.SharePackage
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.sharing.HostGroupShare
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareFriendGroups
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroup
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupAnchor
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupOutcome
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupPrefixLayout
import io.github.nuke.oss.app.tiktok.feature.sharing.ShareGroupTapTracker
import io.github.nuke.oss.app.tiktok.feature.sharing.parseShareGroups
import io.github.nuke.oss.app.tiktok.feature.sharing.resolveShareGroup
import io.github.nuke.oss.app.tiktok.query.ShareGroupQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for sharing to friend groups, with stand-ins for the picker, the IM share service and its callback.
class ShareFriendGroupsTest : HookHarness() {
    class Arguments(@JvmField var sharePackage: SharePackage?)
    class Picker(@Suppress("unused") private val arguments: Arguments) {
        @JvmField val list = RecyclerView()
    }
    class User(private val uid: String) {
        fun getUid() = uid
    }

    interface Callback {
        fun onPreShare(sharePackage: Any?): Boolean
        fun onShareComplete(sharePackage: Any?)
        fun onShareSuccessWithSingleUser(sharePackage: Any?, user: Any?)
        fun onShareSuccessWithUser(sharePackage: Any?, users: List<*>?)
        fun onShareFailedWithUser(sharePackage: Any?, users: List<*>?)
        fun onShareFailed(sharePackage: Any?)
        fun onShareSessionEnd()
    }

    class Service {
        val calls = ArrayList<Pair<SharePackage, List<*>>>()
        var answer: (Callback, List<*>) -> Unit = { callback, users -> callback.onShareSuccessWithUser(null, users) }
        fun shareWithCallBack(sharePackage: SharePackage, users: List<*>, callback: Callback, tip: Any?) {
            calls += sharePackage to users
            check(!callback.onPreShare(sharePackage))
            answer(callback, users)
        }
    }

    class Im(private val service: Any?) {
        fun getShareService() = service
    }

    private val prompts = RecordingPrompts()
    private val prefixes = RecordingFriendPrefixes()
    private val service = Service()
    private var im: Any? = Im(service)
    private var account = 42L
    private val ann = User("1")
    private val bob = User("2")
    private var friends: List<Pair<String, Any>> = listOf("1" to ann, "2" to bob, "3" to User("3"))
    private var friendFailure: HostContactsUnavailable? = null
    private val inlineMain = TaskScheduler { _, task -> task(); AutoCloseable {} }
    private val pending = ArrayList<() -> Unit>()
    private var deferred = false
    private val runner = BackgroundRunner { _, task -> if (deferred) pending += task else task() }

    private val picker = Picker::class.java.getDeclaredConstructor(Arguments::class.java)
    private val send = method(Service::class.java, "shareWithCallBack", SharePackage::class.java, List::class.java, Callback::class.java, Any::class.java)

    private val avatarDirectory = java.nio.file.Files.createTempDirectory("share-avatars").toFile().apply { deleteOnExit() }

    private fun feature(lines: List<String> = listOf("Team = 2, 1", "Solo=3"), avatars: List<String> = emptyList()) = ShareFriendGroups(
        resolverOf(ShareGroupQueries.FRIEND_PICKER to picker, ShareGroupQueries.SHARE_WITH_CALLBACK to send),
        loader, values(ShareFriendGroups.ID, ShareFriendGroups.OPTIONS, ShareFriendGroups.GROUPS to lines, ShareFriendGroups.AVATARS to avatars),
        { friendFailure?.let { throw it }; friends }, { im }, { account }, prefixes, prompts, inlineMain, Logger {}, avatarDirectory, runner,
    )

    @Test fun `a group shows its custom picture or its first friend and a bad picture line is refused`() {
        val name = "0123abcd-0000-4000-8000-0123456789ab.png"
        java.io.File(avatarDirectory, name).writeBytes(byteArrayOf(7, 8))
        enable(feature(avatars = listOf("Team=$name")))
        open()
        val groups = prefixes.attached.single().groups
        assertEquals("file:$name", groups[0].avatar?.key)
        assertEquals(listOf<Byte>(7, 8), groups[0].avatar?.load?.invoke()?.toList())
        assertEquals("uid:3", groups[1].avatar?.key)
        val parsed = io.github.nuke.oss.app.tiktok.feature.sharing.parseShareGroups(listOf("Team=2,1"))
        for (bad in listOf("Nobody=$name", "Team=../secret.png", "Team")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                io.github.nuke.oss.app.tiktok.feature.sharing.parseShareAvatars(listOf(bad), parsed)
            }
        }
    }

    private fun open(sharePackage: SharePackage = SharePackage()): Arguments {
        val arguments = Arguments(sharePackage)
        backend.call(picker, null, arguments)
        return arguments
    }

    @Test fun `the picker gets the groups before its friend list`() {
        enable(feature())
        open()
        val attached = prefixes.attached.single()
        assertTrue(attached.list is RecyclerView)
        assertEquals(listOf("Team" to 2, "Solo" to 1), attached.groups.map { it.name to it.members })
        assertEquals(1, prefixes.installs)
    }

    @Test fun `a tap shares the post to the group's friend objects in the group's order`() {
        enable(feature())
        val sharePackage = SharePackage()
        open(sharePackage)
        prefixes.attached.single().onTap(0)
        val (shared, users) = service.calls.single()
        assertSame(sharePackage, shared)
        assertEquals(listOf<Any>(bob, ann), users)
        assertEquals(R.string.host_notice_share_groups_working, prompts.toasts.first().first)
        assertEquals(R.string.host_notice_share_groups_result to listOf<Any>(2, 0, 0), prompts.toasts.last())
    }

    @Test fun `the host's callbacks are summed per friend`() {
        enable(feature())
        open()
        service.answer = { callback, users ->
            callback.onShareSuccessWithSingleUser(null, users[0])
            callback.onShareFailedWithUser(null, listOf(users[1]))
        }
        prefixes.attached.single().onTap(0)
        assertEquals(R.string.host_notice_share_groups_result to listOf<Any>(1, 1, 0), prompts.toasts.last())
        service.answer = { callback, _ -> callback.onShareFailed(null) }
        prefixes.attached.single().onTap(0)
        assertEquals(R.string.host_notice_share_groups_result to listOf<Any>(0, 2, 0), prompts.toasts.last())
    }

    @Test fun `a friend missing from the friend list stops the whole group`() {
        enable(feature())
        open()
        friends = listOf("2" to bob)
        prefixes.attached.single().onTap(0)
        assertTrue(service.calls.isEmpty())
        assertEquals(R.string.host_notice_share_groups_failed, prompts.toasts.last().first)
    }

    @Test fun `an unreadable friend list is reported and nothing is sent`() {
        enable(feature())
        open()
        friendFailure = HostContactsUnavailable("no contacts")
        prefixes.attached.single().onTap(0)
        assertTrue(service.calls.isEmpty())
        assertEquals(R.string.host_notice_share_groups_failed to listOf<Any>("no contacts"), prompts.toasts.last())
    }

    @Test fun `a group listing the signed-in account is refused`() {
        account = 3
        enable(feature())
        open()
        prefixes.attached.single().onTap(1)
        assertTrue(service.calls.isEmpty())
        assertEquals(R.string.host_notice_share_groups_failed, prompts.toasts.last().first)
    }

    @Test fun `a picker that now shows another post or left the screen sends nothing`() {
        enable(feature())
        val arguments = open()
        arguments.sharePackage = SharePackage()
        prefixes.attached.single().onTap(0)
        assertTrue(service.calls.isEmpty())
        open()
        prefixes.onScreen = false
        prefixes.attached.last().onTap(0)
        assertTrue(service.calls.isEmpty())
        assertEquals(R.string.host_notice_share_groups_failed, prompts.toasts.last().first)
    }

    @Test fun `without the share service nothing is dispatched`() {
        enable(feature())
        open()
        im = Im(null)
        prefixes.attached.single().onTap(0)
        assertEquals(R.string.host_notice_share_groups_failed, prompts.toasts.last().first)
        im = Im("not a share service")
        prefixes.attached.single().onTap(0)
        assertEquals(R.string.host_notice_share_groups_failed, prompts.toasts.last().first)
        assertTrue(service.calls.isEmpty())
    }

    @Test fun `one share runs at a time`() {
        enable(feature())
        open()
        deferred = true
        prefixes.attached.single().onTap(0)
        prefixes.attached.single().onTap(1)
        assertEquals(R.string.host_notice_share_groups_busy, prompts.toasts.last().first)
        pending.removeAt(0)()
        assertEquals(1, service.calls.size)
        prefixes.attached.single().onTap(1)
        assertEquals(1, pending.size)
    }

    @Test fun `a turned-off feature attaches nothing and ignores taps`() {
        val feature = feature()
        enable(feature)
        open()
        val tap = prefixes.attached.single().onTap
        disable(feature)
        open()
        tap(0)
        assertEquals(1, prefixes.attached.size)
        assertTrue(service.calls.isEmpty())
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `an invalid group line keeps the feature from installing`() {
        failsToInstall(feature(listOf("Team=1,x")), "invalid UID")
    }

    @Test fun `no answer from the host leaves the friends unknown`() {
        val share = HostGroupShare(loader, send, { Im(Service().apply { answer = { _, _ -> } }) }, inlineMain)
        val result = share.share(SharePackage(), listOf(ann, bob), setOf("1", "2"), 10)
        assertTrue(result.dispatched)
        assertEquals(2, result.unknown)
        assertTrue(result.error!!.contains("No answer"))
    }

    @Test fun `a share the main thread never ran is not dispatched later`() {
        val tasks = ArrayList<() -> Unit>()
        val service = Service()
        val share = HostGroupShare(loader, send, { Im(service) }, TaskScheduler { _, task -> tasks += task; AutoCloseable {} })
        val result = share.share(SharePackage(), listOf(ann), setOf("1"), 10)
        assertFalse(result.dispatched)
        tasks.single()()
        assertTrue(service.calls.isEmpty())
    }

    @Test fun `group lines are parsed with the legacy limits`() {
        assertEquals(listOf(ShareGroup("A=B", listOf("1", "2"))), parseShareGroups(listOf("A=B = 1, 2, 1,")))
        assertTrue(parseShareGroups(emptyList()).isEmpty())
        val bad = listOf(
            listOf("no separator"), listOf("=1"), listOf("${"n".repeat(31)}=1"), listOf("A="), listOf("A=0"), listOf("A=-4"),
            listOf("A=1", "A=2"), listOf("A=" + (1..101).joinToString(",")), (1..21).map { "G$it=1" },
        )
        for (lines in bad) assertTrue(lines.toString(), runCatching { parseShareGroups(lines) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(20, parseShareGroups((1..20).map { "G$it=1" }).size)
        assertEquals(100, parseShareGroups(listOf("A=" + (1..100).joinToString(","))).single().uids.size)
    }

    @Test fun `a group resolves only when every friend is there exactly once`() {
        val group = ShareGroup("A", listOf("2", "1"))
        assertEquals(listOf("b", "a"), resolveShareGroup(group, listOf("1" to "a", "2" to "b", "3" to "c")))
        assertTrue(runCatching { resolveShareGroup(group, listOf("1" to "a")) }.isFailure)
        assertTrue(runCatching { resolveShareGroup(group, listOf("1" to "a", "2" to "b", "2" to "b2")) }.isFailure)
    }

    @Test fun `the outcome never counts a friend twice or outside the request`() {
        val outcome = ShareGroupOutcome(setOf("1", "2", "3"))
        outcome.record(listOf("1", "9"), success = true)
        outcome.record(listOf("1", "2"), success = false)
        assertEquals(setOf("1"), outcome.accepted)
        assertEquals(setOf("2"), outcome.failed)
        assertEquals(1, outcome.unknownCount)
        outcome.record(listOf("2"), success = true)
        assertEquals(setOf("1", "2"), outcome.accepted)
        assertTrue(outcome.failed.isEmpty())
    }

    @Test fun `the prefix takes room before the first row of a list or a grid`() {
        val list = ShareGroupPrefixLayout(horizontal = false, lanes = 1, groups = 3, cellWidth = 76f, cellHeight = 100f)
        assertEquals(300f, list.inset(0))
        assertEquals(0f, list.inset(1))
        val cells = list.bounds(listOf(ShareGroupAnchor(0, 10f, 400f, 50f), ShareGroupAnchor(1, 10f, 500f, 50f)))
        assertEquals(listOf(100f, 200f, 300f), cells.map { it.top })
        assertTrue(cells[1].contains(20f, 250f))
        val grid = ShareGroupPrefixLayout(horizontal = false, lanes = 2, groups = 3, cellWidth = 76f, cellHeight = 100f)
        assertEquals(200f, grid.inset(1))
        assertEquals(listOf(0, 2), grid.bounds(listOf(ShareGroupAnchor(0, 0f, 200f, 60f))).map { it.index })
        val row = ShareGroupPrefixLayout(horizontal = true, lanes = 1, groups = 2, cellWidth = 76f, cellHeight = 100f)
        assertEquals(listOf(8f, 84f), row.bounds(listOf(ShareGroupAnchor(0, 160f, 0f, 60f))).map { it.left })
        assertTrue(list.bounds(emptyList()).isEmpty())
    }

    @Test fun `only a still release on the same group is a tap`() {
        val taps = ShareGroupTapTracker(slop = 8f)
        taps.down(1, 0f, 0f)
        assertEquals(1, taps.up(1, 4f, 4f))
        taps.down(1, 0f, 0f)
        taps.move(20f, 0f)
        assertNull(taps.up(1, 0f, 0f))
        taps.down(1, 0f, 0f)
        assertNull(taps.up(2, 0f, 0f))
        taps.down(null, 0f, 0f)
        assertNull(taps.up(null, 0f, 0f))
    }
}
