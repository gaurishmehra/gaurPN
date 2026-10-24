package dev.gaurish.outlinelite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SsKeyParserTest {

    private fun b64url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesCurrentOutlineFormat() {
        val userinfo = b64url("chacha20-ietf-poly1305:sea-wind-detective-9")
        val key = SsKeyParser.parse("ss://$userinfo@45.194.32.10:925?outline=1#Singapore%20Node")
        assertNotNull(key)
        key!!  // smart cast for the compiler
        assertEquals("Singapore Node", key.name)
        assertEquals("45.194.32.10", key.host)
        assertEquals(925, key.port)
        assertEquals("chacha20-ietf-poly1305", key.method)
        assertEquals("sea-wind-detective-9", key.password)
    }

    @Test
    fun parsesPlaintextUrlEncodedUserinfo() {
        val key = SsKeyParser.parse("ss://aes-256-gcm%3Ap%40ss%3Aword@vpn.example.org:443#Home")
        assertNotNull(key)
        assertEquals("Home", key!!.name)
        assertEquals("vpn.example.org", key.host)
        assertEquals(443, key.port)
        assertEquals("aes-256-gcm", key.method)
        assertEquals("p@ss:word", key.password)
    }

    @Test
    fun parsesLegacyWholeBodyBase64() {
        val body = b64url("chacha20-ietf-poly1305:key123@10.0.0.1:8388")
        val key = SsKeyParser.parse("ss://$body#Legacy")
        assertNotNull(key)
        assertEquals("Legacy", key!!.name)
        assertEquals("10.0.0.1", key.host)
        assertEquals(8388, key.port)
        assertEquals("key123", key.password)
    }

    @Test
    fun parsesIpv6Host() {
        val userinfo = b64url("chacha20-ietf-poly1305:pw")
        val key = SsKeyParser.parse("ss://$userinfo@[2001:db8::1]:8080")
        assertNotNull(key)
        assertEquals("2001:db8::1", key!!.host)
        assertEquals(8080, key.port)
        assertEquals("2001:db8::1:8080", key.name) // default name = host:port
    }

    @Test
    fun rejectsGarbageAndPlugins() {
        assertNull(SsKeyParser.parse("not-a-key"))
        assertNull(SsKeyParser.parse("ss://simple-obfs%3Ahttp@1.2.3.4:80?plugin=obfs-local#x"))
        assertNull(SsKeyParser.parse("ss://"))
        assertNull(SsKeyParser.parse("ss://####@host:80#ok"))
        assertTrue(SsKeyParser.parse("ss://" + b64url("aes-256-gcm:pw") + "@host:70000") == null)
    }

    @Test
    fun configJsonMatchesOutlineSessionFormat() {
        val key = AccessKey("n", "1.2.3.4", 443, "aes-256-gcm", "quote\"back\\slash")
        val json = key.configJson()
        assertTrue(json.contains("\"host\":\"1.2.3.4\""))
        assertTrue(json.contains("\"port\":443"))
        assertTrue(json.contains("\"method\":\"aes-256-gcm\""))
        assertTrue(json.contains("quote\\\"back\\\\slash"))
    }

    @Test
    fun hostPortParsingCornerCases() {
        assertEquals("example.com" to 1, SsKeyParser.parseHostPort("example.com:1"))
        assertNull(SsKeyParser.parseHostPort("example.com"))
        assertNull(SsKeyParser.parseHostPort("example.com:x"))
    }
}
