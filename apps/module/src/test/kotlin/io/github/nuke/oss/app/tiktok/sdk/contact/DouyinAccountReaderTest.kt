package io.github.nuke.oss.app.tiktok.sdk.contact

import io.github.nuke.oss.app.shared.settings.HostAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinAccountReaderTest {
    // Stand in for Douyin's UrlModel, profile User and account service: the same member names, read by reflection.
    class UrlModel(private val urls: List<Any?>) {
        fun getUrlList(): List<Any?> = urls
    }

    @Suppress("unused")
    class User(private val uid: String, private val name: String) {
        var avatar300x300: UrlModel? = null
        var avatarMedium: UrlModel? = null
        var avatarThumb: UrlModel? = null
        var avatarLarger: UrlModel? = null
        var avatarUrl: String? = null
        fun getUid() = uid
        fun getNickname() = name
    }

    class Service(var userId: String = "9007199254740993", var user: User? = User("9007199254740993", "nick")) {
        var loggedIn = true
        var onUser: () -> Unit = {}
        fun isLogin() = loggedIn
        fun getCurUserId() = userId
        fun getCurUser(): User? = user.also { onUser() }
    }

    class Bare

    private val reports = mutableListOf<ReflectiveOperationException>()

    private fun read(service: Any?) = DouyinAccountReader({ service }) { reports += it }.read()

    @Test fun `a signed-in account is read with its exact ID, nickname and avatar`() {
        val service = Service().apply { user!!.avatarMedium = UrlModel(listOf("https://cdn/avatar")) }
        assertEquals(HostAccount.SignedIn("9007199254740993", "nick", listOf("https://cdn/avatar")), read(service))
        assertTrue(reports.isEmpty())
    }

    @Test fun `only downloadable avatar URLs are kept, in size order and without repeats`() {
        val service = Service().apply {
            user!!.avatar300x300 = UrlModel(listOf("", "resource-key", null, 123))
            user!!.avatarMedium = UrlModel(listOf("  //cdn/medium  ", "https://cdn/medium"))
            user!!.avatarThumb = UrlModel(listOf("http://cdn/thumb"))
            user!!.avatarLarger = UrlModel(listOf("https://cdn/large"))
            user!!.avatarUrl = "https://cdn/direct"
        }
        val account = read(service) as HostAccount.SignedIn
        assertEquals(listOf("https://cdn/medium", "http://cdn/thumb", "https://cdn/large", "https://cdn/direct"), account.avatarUrls)
    }

    @Test fun `a signed-out host never exposes the cached profile`() {
        assertSame(HostAccount.SignedOut, read(Service().apply { loggedIn = false }))
    }

    @Test fun `a profile of another account supplies no nickname or avatar`() {
        val service = Service(user = User("other", "someone else"))
        assertEquals(HostAccount.SignedIn("9007199254740993", "", emptyList()), read(service))
    }

    @Test fun `a profile the host has not loaded keeps the signed-in ID`() {
        assertEquals(HostAccount.SignedIn("9007199254740993", "", emptyList()), read(Service(user = null)))
    }

    @Test fun `an account switch during the read discards the answer`() {
        val service = Service()
        service.onUser = { service.userId = "new-account" }
        assertSame(HostAccount.Unavailable, read(service))
    }

    @Test fun `a blank ID while signed in is no answer`() {
        assertSame(HostAccount.Unavailable, read(Service(userId = "")))
    }

    @Test fun `a missing service or required member is reported and reads as no answer`() {
        assertSame(HostAccount.Unavailable, read(null))
        assertTrue(reports.isEmpty())
        assertSame(HostAccount.Unavailable, read(Bare()))
        assertTrue(reports.single().message!!.contains("isLogin"))
        val failing = DouyinAccountReader({ throw ClassNotFoundException("AccountProxyService") }) { reports += it }
        assertSame(HostAccount.Unavailable, failing.read())
        assertEquals(2, reports.size)
    }
}
