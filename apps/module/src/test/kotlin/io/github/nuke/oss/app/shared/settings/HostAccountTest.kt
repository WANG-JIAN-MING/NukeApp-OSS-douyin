package io.github.nuke.oss.app.shared.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HostAccountTest {
    private val known = HostAccount.SignedIn("123", "old", listOf("https://cdn/old"))

    @Test fun `the same account refreshes its nickname and avatar`() {
        val next = HostAccount.SignedIn("123", "new", listOf("https://cdn/new"))
        assertEquals(next, known.refreshedBy(next))
    }

    @Test fun `the same account keeps what the host has not loaded yet`() {
        assertEquals(known, known.refreshedBy(HostAccount.SignedIn("123", "", emptyList())))
    }

    @Test fun `another account never inherits the previous nickname or avatar`() {
        val next = HostAccount.SignedIn("456", "", emptyList())
        assertEquals(next, known.refreshedBy(next))
    }

    @Test fun `signing out drops the account`() {
        assertSame(HostAccount.SignedOut, known.refreshedBy(HostAccount.SignedOut))
    }

    @Test fun `a read with no answer keeps what is known`() {
        assertSame(known, known.refreshedBy(HostAccount.Unavailable))
        assertSame(HostAccount.SignedOut, HostAccount.SignedOut.refreshedBy(HostAccount.Unavailable))
    }
}
