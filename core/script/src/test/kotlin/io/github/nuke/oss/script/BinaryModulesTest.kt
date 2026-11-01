package io.github.nuke.oss.script

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryModulesTest {
    private val engine = ScriptEngine()

    @After
    fun closeEngine() = engine.close()

    // Runs body inside the entry module and returns everything it logged, one line per info call.
    private fun logsOf(body: String): List<String> {
        val listener = RecordingListener()
        engine.run(
            """
            import { info } from "nuke:log";
            import * as encoding from "nuke:encoding";
            import * as crypto from "nuke:crypto";
            $body
            info("done");
            """,
            listener,
        ).awaitStart()
        listener.awaitLog("done")
        return listener.logs.map { it.removePrefix("INFO ") }.filter { it != "done" }
    }

    @Test
    fun encodingRoundTripsTextAndBytes() {
        val logs = logsOf(
            """
            const bytes = encoding.encodeUtf8("héllo");
            info(bytes instanceof Uint8Array, bytes.length, encoding.decodeUtf8(bytes));
            info(encoding.encodeBase64("hello"), encoding.encodeBase64(new Uint8Array([251, 255]), { url: true }));
            info(encoding.decodeUtf8(encoding.decodeBase64("aGVsbG8=")), encoding.decodeBase64("-_8").join(","));
            info(encoding.encodeHex([0, 171, 255]), encoding.encodeHex("A", true), encoding.decodeHex("00abff").join(","));
            try { encoding.decodeHex("zz"); } catch (e) { info(e.code); }
            try { encoding.decodeUtf8("text"); } catch (e) { info(e.code, e.message); }
            """,
        )
        assertEquals(
            listOf(
                "true 6 héllo",
                "aGVsbG8= -_8",
                "hello 251,255",
                "00abff 41 0,171,255",
                "INVALID_ARGUMENT",
                "INVALID_ARGUMENT bytes must be a byte array",
            ),
            logs,
        )
    }

    @Test
    fun cryptoMatchesKnownVectors() {
        val logs = logsOf(
            """
            info(encoding.encodeHex(crypto.digest("sha-256", "abc")));
            info(encoding.encodeHex(crypto.hmac("SHA-256", "key", "The quick brown fox jumps over the lazy dog")));
            info(crypto.randomBytes(16).length, /^[0-9a-f-]{36}$/.test(crypto.randomUUID()));
            info(crypto.timingSafeEqual([1, 2], [1, 2]), crypto.timingSafeEqual([1, 2], [1, 3]));
            try { crypto.digest("SHA-3", "x"); } catch (e) { info(e.code); }
            """,
        )
        assertEquals(
            listOf(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                "16 true",
                "true false",
                "INVALID_ARGUMENT",
            ),
            logs,
        )
    }

    @Test
    fun aesGcmRoundTripsAndRejectsTampering() {
        val logs = logsOf(
            """
            const key = crypto.randomBytes(32);
            const iv = crypto.randomBytes(12);
            const sealed = crypto.aesGcmEncrypt({ key, iv, data: "secret", aad: "header" });
            info(sealed.length, encoding.decodeUtf8(crypto.aesGcmDecrypt({ key, iv, data: sealed, aad: "header" })));
            sealed[0] ^= 1;
            try { crypto.aesGcmDecrypt({ key, iv, data: sealed, aad: "header" }); } catch (e) { info(e.code); }
            try { crypto.aesGcmEncrypt({ key: [1, 2, 3], iv, data: "x" }); } catch (e) { info(e.message); }
            """,
        )
        assertEquals(listOf("22 secret", "INVALID_ARGUMENT", "key must be 16, 24 or 32 bytes"), logs)
    }
}
