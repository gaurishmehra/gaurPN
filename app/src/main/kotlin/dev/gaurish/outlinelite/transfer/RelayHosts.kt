package dev.gaurish.outlinelite.transfer

/**
 * Guard for relay addresses. The cluster relay is only ever supposed to be
 * reachable inside the VPN tunnel, so its host must be a loopback,
 * private, link-local, or documentation/reserved address. A public address
 * or a hostname would mean traffic leaves the tunnel (or the stored address
 * was tampered with), so the connection is refused.
 *
 * Pure Kotlin — no Android imports — so the ranges are JVM unit-testable.
 */
object RelayHosts {

    fun isInTunnelHost(host: String): Boolean {
        val cleaned = host.trim()
        if (cleaned.isEmpty()) return false
        val hostOnly = stripPort(cleaned)
        val v4 = parseIpv4(hostOnly)
        if (v4 != null) return isTunnelV4(v4)
        val v6 = parseIpv6(hostOnly) ?: return false
        return isTunnelV6(v6)
    }

    /**
     * Strips a trailing `:port`. Bracketed IPv6 keeps everything inside the
     * brackets; a bare (unbracketed) IPv6 literal has more than one colon
     * and is kept whole.
     */
    private fun stripPort(host: String): String {
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            if (close < 0) return host
            return host.substring(1, close)
        }
        if (host.count { it == ':' } == 1) {
            return host.substringBeforeLast(':')
        }
        return host
    }

    private fun parseIpv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            val v = part.toIntOrNull() ?: return null
            if (v < 0 || v > 255) return null
            out[i] = v
        }
        return out
    }

    private fun isTunnelV4(octets: IntArray): Boolean = when {
        octets[0] == 127 -> true // 127/8 loopback
        octets[0] == 10 -> true // 10/8 RFC1918
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16/12 RFC1918
        octets[0] == 192 && octets[1] == 168 -> true // 192.168/16 RFC1918
        octets[0] == 169 && octets[1] == 254 -> true // 169.254/16 link-local
        octets[0] == 192 && octets[1] == 0 && octets[2] == 2 -> true // 192.0.2/24 TEST-NET-1
        octets[0] == 198 && octets[1] == 51 && octets[2] == 100 -> true // 198.51.100/24 TEST-NET-2
        octets[0] == 203 && octets[1] == 0 && octets[2] == 113 -> true // 203.0.113/24 TEST-NET-3
        else -> false
    }

    /** Parses an IPv6 literal (with `::` expansion) into 8 groups, or null. */
    private fun parseIpv6(s: String): IntArray? {
        val body = s.substringBefore('%') // drop a zone index such as %eth0
        if (body.isEmpty()) return null
        val sections = body.split("::")
        if (sections.size > 2) return null
        val head = parseGroups(sections[0]) ?: return null
        val tail = if (sections.size == 2) parseGroups(sections[1]) ?: return null else emptyList()
        val total = head.size + tail.size
        if (sections.size == 2) {
            if (total > 7) return null
        } else if (total != 8) {
            return null
        }
        val groups = IntArray(8)
        var idx = 0
        head.forEach { groups[idx++] = it }
        if (sections.size == 2) {
            repeat(8 - total) { groups[idx++] = 0 }
        }
        tail.forEach { groups[idx++] = it }
        return groups
    }

    private fun parseGroups(section: String): List<Int>? {
        if (section.isEmpty()) return emptyList()
        val tokens = section.split(':')
        val out = ArrayList<Int>(tokens.size)
        tokens.forEachIndexed { i, token ->
            if (token.contains('.')) {
                // IPv4-mapped tail such as ::ffff:1.2.3.4; must be the last group.
                if (i != tokens.size - 1) return null
                val v4 = parseIpv4(token) ?: return null
                out.add((v4[0] shl 8) or v4[1])
                out.add((v4[2] shl 8) or v4[3])
            } else {
                if (token.isEmpty() || token.length > 4) return null
                val v = token.toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                out.add(v)
            }
        }
        return out
    }

    private fun isTunnelV6(groups: IntArray): Boolean = when {
        groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 &&
            groups[4] == 0 && groups[5] == 0 && groups[6] == 0 && groups[7] == 1 -> true // ::1
        (groups[0] and 0xFFC0) == 0xFE80 -> true // fe80::/10 link-local
        (groups[0] and 0xFE00) == 0xFC00 -> true // fc00::/7 ULA
        else -> false
    }
}
