package io.github.nuke.oss.ui.hostsettings

import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationPickerTest {
    private val ann = HostFriend("1", "Ann", null, 3, "0:1:1")
    private val bob = HostFriend("2", "Bob", null, 30, "0:1:2")
    private val cat = HostFriend("3", "Cat", null, null, "0:1:3")
    private val group = HostGroup("g1", "Weekend", null)
    private val contacts = HostContactList(listOf(ann, bob, cat), listOf(group))

    @Test fun `friends sort by streak days, an unknown count last`() {
        assertEquals(listOf(bob, ann, cat), friendChoices(contacts, emptySet(), FriendSort.DAYS, ""))
    }

    @Test fun `selected friends can be listed first`() {
        assertEquals(listOf(cat, bob, ann), friendChoices(contacts, setOf("0:1:3"), FriendSort.SELECTED, ""))
    }

    @Test fun `a selected chat in neither list stays listed under its ID`() {
        val orphan = HostFriend("0:1:9", "0:1:9", null, null, "0:1:9")
        assertEquals(listOf(bob, ann, orphan, cat), friendChoices(contacts, setOf("0:1:9", "g1"), FriendSort.DAYS, ""))
    }

    @Test fun `search matches names and IDs`() {
        assertEquals(listOf(ann), friendChoices(contacts, emptySet(), FriendSort.DAYS, " ann "))
        assertEquals(listOf(bob), friendChoices(contacts, emptySet(), FriendSort.DAYS, "0:1:2"))
        assertEquals(listOf(group), groupChoices(contacts.groups, "week"))
        assertEquals(emptyList<HostGroup>(), groupChoices(contacts.groups, "nothing"))
    }
}
