package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHostsTest {

    @Test
    fun relayDefaultDocumentationAddressIsInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("198.51.100.1"))
    }

    @Test
    fun adguardVpnPrivateAddressIsInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("10.0.0.182"))
    }

    @Test
    fun loopbackIsInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("127.0.0.1"))
        assertTrue(RelayHosts.isInTunnelHost("127.8.9.10"))
    }

    @Test
    fun otherPrivateRangesAreInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("172.16.0.1"))
        assertTrue(RelayHosts.isInTunnelHost("172.31.255.255"))
        assertTrue(RelayHosts.isInTunnelHost("192.168.1.1"))
        assertTrue(RelayHosts.isInTunnelHost("169.254.7.7"))
        assertTrue(RelayHosts.isInTunnelHost("192.0.2.9"))
        assertTrue(RelayHosts.isInTunnelHost("203.0.113.5"))
    }

    @Test
    fun privateRangeBoundariesAreEnforced() {
        assertFalse(RelayHosts.isInTunnelHost("172.15.255.255"))
        assertFalse(RelayHosts.isInTunnelHost("172.32.0.1"))
        assertFalse(RelayHosts.isInTunnelHost("192.169.0.1"))
    }

    @Test
    fun bracketedIpv6UlaIsInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("[fd00::1]"))
        assertTrue(RelayHosts.isInTunnelHost("[fc00::1]"))
    }

    @Test
    fun ipv6LoopbackAndLinkLocalAreInTunnel() {
        assertTrue(RelayHosts.isInTunnelHost("::1"))
        assertTrue(RelayHosts.isInTunnelHost("[::1]"))
        assertTrue(RelayHosts.isInTunnelHost("[fe80::1]"))
        assertTrue(RelayHosts.isInTunnelHost("[febf::1]"))
    }

    @Test
    fun globalIpv6IsNotInTunnel() {
        assertFalse(RelayHosts.isInTunnelHost("[2001:db8::1]"))
        assertFalse(RelayHosts.isInTunnelHost("[2607:f8b0:4004:800::200e]"))
    }

    @Test
    fun publicIpv4IsNotInTunnel() {
        assertFalse(RelayHosts.isInTunnelHost("8.8.8.8"))
        assertFalse(RelayHosts.isInTunnelHost("1.1.1.1"))
        assertFalse(RelayHosts.isInTunnelHost("198.51.101.1"))
    }

    @Test
    fun hostnamesAreNotInTunnel() {
        assertFalse(RelayHosts.isInTunnelHost("example.com"))
        assertFalse(RelayHosts.isInTunnelHost("relay.local"))
    }

    @Test
    fun hostWithPortIsAccepted() {
        assertTrue(RelayHosts.isInTunnelHost("198.51.100.1:9876"))
        assertTrue(RelayHosts.isInTunnelHost("[::1]:9876"))
        assertTrue(RelayHosts.isInTunnelHost("10.0.0.182:9876"))
        assertFalse(RelayHosts.isInTunnelHost("8.8.8.8:9876"))
        assertFalse(RelayHosts.isInTunnelHost("example.com:9876"))
    }

    @Test
    fun junkInputIsNotInTunnel() {
        assertFalse(RelayHosts.isInTunnelHost(""))
        assertFalse(RelayHosts.isInTunnelHost("   "))
        assertFalse(RelayHosts.isInTunnelHost("198.51.100"))
        assertFalse(RelayHosts.isInTunnelHost("198.51.100.999"))
        assertFalse(RelayHosts.isInTunnelHost("198.51.100.1.2"))
    }
}
