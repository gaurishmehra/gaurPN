package dev.gaurish.outlinelite.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanBypassRoutesTest {

    private fun ipToLong(ipStr: String): Long {
        val bytes = InetAddress.getByName(ipStr).address
        var result = 0L
        for (b in bytes) {
            result = (result shl 8) or (b.toInt() and 0xFF).toLong()
        }
        return result
    }

    private fun matchesRoute(ipStr: String, prefixStr: String, prefixLen: Int): Boolean {
        val ip = ipToLong(ipStr)
        val prefix = ipToLong(prefixStr)
        val mask = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xFFFFFFFFL
        return (ip and mask) == (prefix and mask)
    }

    private fun isRouted(ipStr: String): Boolean {
        return LanBypassRoutes.EXCLUDE_LAN_IPV4_ROUTES.any { (prefix, len) ->
            matchesRoute(ipStr, prefix, len)
        }
    }

    @Test
    fun testExcludedSubnetsAreNeverRouted() {
        // RFC 1918 10.0.0.0/8
        assertFalse(isRouted("10.0.0.1"))
        assertFalse(isRouted("10.255.255.255"))

        // RFC 1918 172.16.0.0/12
        assertFalse(isRouted("172.16.0.1"))
        assertFalse(isRouted("172.31.255.255"))

        // RFC 1918 192.168.0.0/16
        assertFalse(isRouted("192.168.0.1"))
        assertFalse(isRouted("192.168.1.100"))
        assertFalse(isRouted("192.168.255.255"))

        // RFC 3927 Link-Local 169.254.0.0/16
        assertFalse(isRouted("169.254.0.1"))
        assertFalse(isRouted("169.254.1.1"))
        assertFalse(isRouted("169.254.169.254"))
        assertFalse(isRouted("169.254.255.255"))

        // Loopback 127.0.0.0/8 (handled by kernel, but ensure not in list)
        assertFalse(isRouted("127.0.0.1"))
    }

    @Test
    fun testPublicIpsAreRouted() {
        assertTrue(isRouted("1.1.1.1"))
        assertTrue(isRouted("8.8.8.8"))
        assertTrue(isRouted("142.250.190.46"))
        assertTrue(isRouted("168.1.1.1"))
        assertTrue(isRouted("169.253.255.255")) // right before link-local
        assertTrue(isRouted("169.255.0.0"))     // right after link-local
        assertTrue(isRouted("172.15.255.255")) // right before 172.16.0.0/12
        assertTrue(isRouted("172.32.0.0"))     // right after 172.16.0.0/12
        assertTrue(isRouted("192.167.255.255")) // right before 192.168.0.0/16
        assertTrue(isRouted("192.169.0.0"))     // right after 192.168.0.0/16
    }
}
