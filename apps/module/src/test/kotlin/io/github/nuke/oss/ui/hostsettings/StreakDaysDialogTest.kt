package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.settings.HostFriend
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakDaysDialogTest {
    private val ann = HostFriend("1", "Ann", null, 3, "0:1:1")
    private val bob = HostFriend("2", "Bob", null, 30, "0:1:2")
    private val cat = HostFriend("3", "Cat", null, null, "0:1:3")

    @Test fun `stored lines read as the every-chat number and one number per chat`() {
        val draft = streakDaysOf(listOf("*=100", "0:1:1=7", "0:1:2 = 0"))
        assertEquals("100", draft.all)
        assertEquals(mapOf("0:1:1" to "7", "0:1:2" to "0"), draft.chats)
    }

    @Test fun `lines that do not read are left out, not guessed at`() {
        val draft = streakDaysOf(listOf("no equals sign", "=5", "a=x", "b=100000", "c=-1", "d=12"))
        assertEquals("", draft.all)
        assertEquals(mapOf("d" to "12"), draft.chats)
    }

    @Test fun `the lines to store put the every-chat number first and drop leading zeros and empty boxes`() {
        val draft = StreakDaysDraft("007", linkedMapOf("0:1:1" to "12", "0:1:2" to "", "0:1:3" to "0"))
        assertEquals(listOf("*=7", "0:1:1=12", "0:1:3=0"), streakDayLines(draft))
        assertEquals(emptyList<String>(), streakDayLines(StreakDaysDraft("", emptyMap())))
    }

    @Test fun `what is read can be stored again unchanged`() {
        val lines = listOf("*=100", "0:1:1=7", "0:1:2=0")
        assertEquals(lines, streakDayLines(streakDaysOf(lines)))
    }

    @Test fun `a box takes digits only and five at most`() {
        assertEquals("12345", streakDigits("12a3-4 5678"))
        assertEquals("", streakDigits("abc"))
    }

    @Test fun `friends with a number of their own come first, then by the real streak`() {
        val draft = StreakDaysDraft("", mapOf("0:1:3" to "5"))
        assertEquals(listOf(cat, bob, ann), streakFriends(listOf(ann, bob, cat), draft, ""))
        assertEquals(listOf(bob, ann, cat), streakFriends(listOf(ann, bob, cat), StreakDaysDraft("", emptyMap()), ""))
    }

    @Test fun `a search narrows by name, user ID or conversation ID`() {
        val draft = StreakDaysDraft("", emptyMap())
        assertEquals(listOf(bob), streakFriends(listOf(ann, bob, cat), draft, "bo"))
        assertEquals(listOf(cat), streakFriends(listOf(ann, bob, cat), draft, "0:1:3"))
        assertEquals(emptyList<HostFriend>(), streakFriends(listOf(ann, bob, cat), draft, "zzz"))
    }
}
