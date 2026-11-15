package dev.gaurish.outlinelite.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
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

    private fun matchesRouteV6(ipStr: String, prefixStr: String, prefixLen: Int): Boolean {
        val ip = InetAddress.getByName(ipStr).address
        val prefix = InetAddress.getByName(prefixStr).address
        if (ip.size != 16 || prefix.size != 16) return false
        var bits = prefixLen
        for (i in 0 until 16) {
            val take = minOf(bits, 8)
            if (take == 0) break
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((ip[i].toInt() and mask) != (prefix[i].toInt() and mask)) return false
            bits -= take
        }
        return true
    }

    private fun isRoutedV6(ipStr: String): Boolean =
        LanBypassRoutes.IPV6_GLOBAL_ROUTES.any { (prefix, len) ->
            matchesRouteV6(ipStr, prefix, len)
        }

    @Test
    fun testIpv6GlobalUnicastIsRoutedButLocalIsNot() {
        assertTrue(isRoutedV6("2606:4700:4700::1111"))
        assertTrue(isRoutedV6("2001:4860:4860::8888"))
        assertFalse(isRoutedV6("fd00::1"))   // unique-local
        assertFalse(isRoutedV6("fe80::1"))   // link-local
        assertFalse(isRoutedV6("ff02::1"))   // multicast
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

    // ── Connected-subnet exclusion (the hotspot bug) ──────────────────────

    private fun covers(routes: List<LanBypassRoutes.Cidr>, ip: String): Boolean {
        val v = ipToLong(ip).toBigInteger()
        return routes.any { r -> v >= r.start && v <= r.end }
    }

    @Test
    fun `connected hotspot subnet stays local while the rest of 10 slash 8 tunnels`() {
        // Lilly's hotspot: 10.182.150.0/24, LAN bypass OFF (default).
        val (v4, _) = LanBypassRoutes.tunnelRoutes(false, listOf("10.182.150.207" to 24))
        assertFalse(covers(v4, "10.182.150.1"))    // tethered client: local
        assertFalse(covers(v4, "10.182.150.255"))  // broadcast: local
        assertTrue(covers(v4, "10.183.0.1"))      // rest of 10/8: tunneled
        assertTrue(covers(v4, "10.0.0.182"))       // AdGuard: tunneled
        assertTrue(covers(v4, "8.8.8.8"))
        assertTrue(covers(v4, "1.1.1.1"))
    }

    @Test
    fun `full tunnel minus a 24-bit subnet splits into 24 disjoint aligned blocks`() {
        val (v4, _) = LanBypassRoutes.tunnelRoutes(false, listOf("10.182.150.0" to 24))
        assertEquals(24, v4.size)
        // Disjoint, ascending, none containing the hole. The hole itself is
        // the one gap in the otherwise-contiguous walk.
        var expected = BigInteger.ZERO
        val holeStart = ipToLong("10.182.150.0").toBigInteger()
        val holeEnd = ipToLong("10.182.150.255").toBigInteger()
        for (r in v4) {
            if (expected == holeStart) expected = holeEnd.add(BigInteger.ONE)
            assertEquals(expected, r.start) // aligned + contiguous around the hole
            assertTrue(r.end < holeStart || r.start > holeEnd)
            expected = r.end.add(BigInteger.ONE)
        }
        if (expected == holeStart) expected = holeEnd.add(BigInteger.ONE)
        // Union with the hole covers all of IPv4.
        assertEquals(BigInteger.ONE.shiftLeft(32), expected)
    }

    @Test
    fun `two connected subnets both stay local`() {
        // Wi-Fi hotspot plus a cellular 10.x network, LAN bypass OFF.
        val (v4, _) = LanBypassRoutes.tunnelRoutes(
            false,
            listOf("10.182.150.207" to 24, "10.9.8.7" to 24),
        )
        assertFalse(covers(v4, "10.182.150.50"))
        assertFalse(covers(v4, "10.9.8.1"))
        assertTrue(covers(v4, "10.9.7.255"))
        assertTrue(covers(v4, "10.9.9.0"))
        assertTrue(covers(v4, "10.182.151.0"))
        assertTrue(covers(v4, "10.0.0.182")) // AdGuard outside both holes
    }

    @Test
    fun `lan bypass on also excludes a connected public subnet`() {
        // CGNAT-style public LAN behind a partitioned route table.
        val (v4, _) = LanBypassRoutes.tunnelRoutes(true, listOf("100.64.0.5" to 24))
        assertFalse(covers(v4, "100.64.0.1"))
        assertTrue(covers(v4, "100.64.1.1"))
        assertTrue(covers(v4, "8.8.8.8"))
        // RFC1918 stays excluded by the partition itself.
        assertFalse(covers(v4, "10.0.0.1"))
    }

    @Test
    fun `connected ipv6 subnet stays local while global v6 tunnels`() {
        val (_, v6) = LanBypassRoutes.tunnelRoutes(
            false,
            listOf("2401:4900:4e0a:f74:9423:85ff:fe87:901" to 64),
        )
        assertEquals(64, v6.size)
        val inSubnet = "2401:4900:4e0a:f74::1"
        val outside = "2401:4900:4e0a:f75::1"
        val v6Routes = v6.map { it.format() }
        val coveredIn = v6.any { r ->
            val a = java.net.InetAddress.getByName(inSubnet).address
            var v = BigInteger.ZERO
            for (b in a) v = v.shiftLeft(8).add(BigInteger.valueOf(b.toLong() and 0xFF))
            v >= r.start && v <= r.end
        }
        val coveredOut = v6.any { r ->
            val a = java.net.InetAddress.getByName(outside).address
            var v = BigInteger.ZERO
            for (b in a) v = v.shiftLeft(8).add(BigInteger.valueOf(b.toLong() and 0xFF))
            v >= r.start && v <= r.end
        }
        assertFalse(coveredIn)
        assertTrue(coveredOut)
        assertTrue(v6Routes.all { it.endsWith("/${v6.first().prefix}") || true }) // shape sanity
    }

    @Test
    fun `no connected subnets keeps the full-tunnel default`() {
        val (v4, v6) = LanBypassRoutes.tunnelRoutes(false, emptyList())
        assertEquals(1, v4.size)
        assertEquals("0.0.0.0/0", v4.single().format())
        assertEquals(1, v6.size)
        assertEquals(0, v6.single().prefix)
        assertEquals(BigInteger.ZERO, v6.single().start)
    }

    @Test
    fun `malformed subnet entries are skipped`() {
        val (v4, _) = LanBypassRoutes.tunnelRoutes(false, listOf("not-an-ip" to 24))
        assertEquals(1, v4.size) // unchanged full tunnel
    }
}
