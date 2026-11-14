package dev.gaurish.outlinelite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequestTest {

    private fun request(
        relay: String = "198.51.100.1:9876",
        token: String = "cluster-secret-token-0123456789",
        dns: String = "10.0.0.182",
        name: String = "Pixel",
    ) = PairingRequest(relay = relay, token = token, dns = dns, name = name)

    @Test
    fun validRequestPasses() {
        assertTrue(request().isValid())
    }

    @Test
    fun blankRelayAndDnsAreOptional() {
        assertTrue(request(relay = "", dns = "").isValid())
    }

    @Test
    fun missingTokenIsInvalid() {
        assertFalse(request(token = "").isValid())
        assertFalse(request(token = "   ").isValid())
    }

    @Test
    fun relayWithoutPortIsInvalid() {
        assertFalse(request(relay = "example.com").isValid())
        assertFalse(request(relay = "198.51.100.1").isValid())
    }

    @Test
    fun relayWithWhitespaceIsInvalid() {
        assertFalse(request(relay = "198.51.100.1 :9876").isValid())
        assertFalse(request(relay = "my relay:9876").isValid())
    }

    @Test
    fun bracketedIpv6RelayIsValid() {
        assertTrue(request(relay = "[::1]:9876").isValid())
        assertTrue(request(relay = "[fd00::1]:9876").isValid())
    }

    @Test
    fun unbracketedIpv6RelayIsInvalid() {
        // Colons only belong inside brackets; otherwise host and port cannot be told apart.
        assertFalse(request(relay = "::1:9876").isValid())
    }

    @Test
    fun dnsWithSpacesIsInvalid() {
        assertFalse(request(dns = "10.0.0.182 10.0.0.1").isValid())
    }

    @Test
    fun commaSeparatedDnsIsValid() {
        assertTrue(request(dns = "10.0.0.182,10.0.0.1").isValid())
    }

    @Test
    fun maskedTokenHidesShortTokensEntirely() {
        assertEquals("••••", request(token = "short").maskedToken())
        assertEquals("••••", request(token = "12345678").maskedToken())
        assertEquals("••••", request(token = "").maskedToken())
    }

    @Test
    fun maskedTokenShowsOnlyEndsOfLongTokens() {
        assertEquals("abcd…wxyz", request(token = "abcdefghijklmnopqrstuvwxyz").maskedToken())
        assertEquals("abcd…fghi", request(token = "abcdefghi").maskedToken())
    }
}
