package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.tiktok.protocol.ProtocolQueries
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.MiniAppQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokDexQueriesTest {
    @Test fun `the generated catalog names every query after its object and property, once`() {
        assertTrue(generatedDexQueries.keys.all { Regex("[A-Z][A-Za-z]*Queries\\.[A-Z][A-Z0-9_]*").matches(it) })
        // A resolver rejects a query object registered under two names.
        val resolver = DexResolver(object : DexCache {
            override fun get(key: String): String? = null
            override fun put(key: String, descriptor: String) {}
        }, "scope", { _, _, _ -> error("No search") }, generatedDexQueries)
        assertEquals("FeedQueries.RESUME_GATE", resolver.nameOf(FeedQueries.RESUME_GATE))
        assertEquals("ProtocolQueries.RAW_DISPATCH", resolver.nameOf(ProtocolQueries.RAW_DISPATCH))
        assertEquals("MiniAppQueries.GAME_FAILURE", resolver.nameOf(MiniAppQueries.GAME_FAILURE))
    }
}
