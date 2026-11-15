package dev.gaurish.outlinelite.tunnel

import android.net.VpnService
import java.math.BigInteger
import java.net.InetAddress

/**
 * Route definitions for full-tunnel vs split LAN access.
 *
 * When LAN access is disabled (default), 0.0.0.0/0 is routed into the VPN TUN.
 * When LAN access is enabled, IPv4 is partitioned to exclude private subnets:
 * - 10.0.0.0/8 (RFC 1918)
 * - 172.16.0.0/12 (RFC 1918)
 * - 192.168.0.0/16 (RFC 1918)
 * - 169.254.0.0/16 (Link-local)
 * - 127.0.0.0/8 (Loopback)
 *
 * Independently of the toggle, the DIRECTLY-CONNECTED subnets of the
 * underlying networks are always kept out of the tunnel: a phone running a
 * hotspot (or sitting on any 10.x/100.x network) must keep its own local
 * subnet reachable, or tethered clients and the local gateway black-hole the
 * moment the VPN claims 0/0 (or 10/8).
 */
object LanBypassRoutes {

    /**
     * IPv6 global-unicast space. Routing only 2000::/3 (plus the NAT64
     * prefixes) keeps link-local (fe80::/10), unique-local (fc00::/7), and
     * multicast (ff00::/8) on the physical network when LAN access is on.
     */
    val IPV6_GLOBAL_ROUTES = listOf(
        "2000::" to 3,
        "64:ff9b::" to 96,
        "64:ff9b:1::" to 48,
    )

    val EXCLUDE_LAN_IPV4_ROUTES = listOf(
        "1.0.0.0" to 8,
        "2.0.0.0" to 7,
        "4.0.0.0" to 6,
        "8.0.0.0" to 7,
        "11.0.0.0" to 8,
        "12.0.0.0" to 6,
        "16.0.0.0" to 4,
        "32.0.0.0" to 3,
        "64.0.0.0" to 3,
        "96.0.0.0" to 4,
        "112.0.0.0" to 5,
        "120.0.0.0" to 6,
        "124.0.0.0" to 7,
        "126.0.0.0" to 8,
        "128.0.0.0" to 3,
        "160.0.0.0" to 5,
        "168.0.0.0" to 8,
        "169.0.0.0" to 9,
        "169.128.0.0" to 10,
        "169.192.0.0" to 11,
        "169.224.0.0" to 12,
        "169.240.0.0" to 13,
        "169.248.0.0" to 14,
        "169.252.0.0" to 15,
        "169.255.0.0" to 16,
        "170.0.0.0" to 7,
        "172.0.0.0" to 12,
        "172.32.0.0" to 11,
        "172.64.0.0" to 10,
        "172.128.0.0" to 9,
        "173.0.0.0" to 8,
        "174.0.0.0" to 7,
        "176.0.0.0" to 4,
        "192.0.0.0" to 9,
        "192.128.0.0" to 11,
        "192.160.0.0" to 13,
        "192.169.0.0" to 16,
        "192.170.0.0" to 15,
        "192.172.0.0" to 14,
        "192.176.0.0" to 12,
        "192.192.0.0" to 10,
        "193.0.0.0" to 8,
        "194.0.0.0" to 7,
        "196.0.0.0" to 6,
        "200.0.0.0" to 5,
        "208.0.0.0" to 4
    )

    // ── Pure CIDR interval math (JVM-testable) ────────────────────────────

    /** A CIDR block as a numeric range [start, end], width 32 or 128 bits. */
    data class Cidr(val start: BigInteger, val prefix: Int, val width: Int) {
        val end: BigInteger get() = start.add(BigInteger.ONE.shiftLeft(width - prefix)).subtract(BigInteger.ONE)

        fun format(): String {
            val padded = ByteArray(width / 8)
            val b = start.toByteArray() // minimal two's complement; may carry an extra leading zero
            val src = if (b.size > padded.size) b.copyOfRange(b.size - padded.size, b.size) else b
            System.arraycopy(src, 0, padded, padded.size - src.size, src.size)
            val addr = InetAddress.getByAddress(padded).hostAddress
            return "$addr/$prefix"
        }

        companion object {
            fun of(cidr: String, width: Int): Cidr {
                // Literal validation: never hand a non-IP string to
                // InetAddress.getByName, which would attempt a DNS lookup.
                if (cidr.any { it !in "0123456789abcdefABCDEF:./" }) {
                    throw IllegalArgumentException("not an IP literal: $cidr")
                }
                val (addr, prefix) = cidr.split("/")
                val a = InetAddress.getByName(addr).address
                var v = BigInteger.ZERO
                for (b in a) v = v.shiftLeft(8).add(BigInteger.valueOf((b.toLong() and 0xFF)))
                val p = prefix.toInt()
                require(p in 0..width) { "prefix $p out of range for width $width" }
                // LinkProperties give HOST addresses; mask to the network
                // prefix so the range starts at the subnet boundary.
                val hostBits = BigInteger.ONE.shiftLeft(width - p).subtract(BigInteger.ONE)
                return Cidr(v.and(hostBits.not()), p, width)
            }
        }
    }

    /** Splits [start, end] into the minimal list of aligned CIDR blocks. */
    private fun rangeToCidrs(start: BigInteger, end: BigInteger, width: Int): List<Cidr> {
        val out = mutableListOf<Cidr>()
        var s = start
        val limit = end.add(BigInteger.ONE)
        while (s < limit) {
            // Largest aligned block that fits: limited by alignment of s and remaining size.
            var size = BigInteger.ONE
            var p = width
            while (p > 0) {
                val nextSize = size.shiftLeft(1)
                val aligned = s.and(nextSize.subtract(BigInteger.ONE)).signum() == 0
                if (!aligned || s.add(nextSize) > limit) break
                size = nextSize
                p--
            }
            out += Cidr(s, p, width)
            s = s.add(size)
        }
        return out
    }

    /**
     * Removes [hole] from every block in [base], returning the minimal CIDR
     * list whose union is base minus hole. Overlapping base blocks are fine.
     */
    fun subtract(base: List<Cidr>, hole: Cidr): List<Cidr> {
        val out = mutableListOf<Cidr>()
        for (b in base) {
            if (b.end < hole.start || hole.end < b.start) {
                out += b
                continue
            }
            val lo = maxOf(b.start, hole.start)
            val hi = minOf(b.end, hole.end)
            if (lo > b.start) out += rangeToCidrs(b.start, lo.subtract(BigInteger.ONE), b.width)
            if (hi < b.end) out += rangeToCidrs(hi.add(BigInteger.ONE), b.end, b.width)
        }
        return out
    }

    /**
     * Computes the routes to claim in the TUN. Pure: [connectedSubnets] are
     * (address, prefix) pairs like ("10.182.150.207", 24).
     */
    fun tunnelRoutes(
        allowLanAccess: Boolean,
        connectedSubnets: List<Pair<String, Int>>,
    ): Pair<List<Cidr>, List<Cidr>> {
        var v4 = if (allowLanAccess) {
            EXCLUDE_LAN_IPV4_ROUTES.map { Cidr.of("${it.first}/${it.second}", 32) }
        } else {
            listOf(Cidr.of("0.0.0.0/0", 32))
        }
        var v6 = if (allowLanAccess) {
            IPV6_GLOBAL_ROUTES.map { Cidr.of("${it.first}/${it.second}", 128) }
        } else {
            listOf(Cidr.of("::/0", 128))
        }
        for ((addr, prefix) in connectedSubnets) {
            val isV4 = !addr.contains(":")
            val width = if (isV4) 32 else 128
            val hole = runCatching { Cidr.of("$addr/$prefix", width) }.getOrNull() ?: continue
            if (isV4) v4 = subtract(v4, hole) else v6 = subtract(v6, hole)
        }
        return v4 to v6
    }

    fun applyRoutes(
        builder: VpnService.Builder,
        allowLanAccess: Boolean,
        dnsServers: List<String> = emptyList(),
        connectedSubnets: List<Pair<String, Int>> = emptyList(),
    ) {
        val (v4, v6) = tunnelRoutes(allowLanAccess, connectedSubnets)
        for (cidr in v4) {
            runCatching { builder.addRoute(cidr.format().substringBefore("/"), cidr.prefix) }
        }
        for (cidr in v6) {
            runCatching { builder.addRoute(cidr.format().substringBefore("/"), cidr.prefix) }
        }

        // A /32 (or /128) host route is more specific than the LAN-bypass
        // excludes, so the resolver stays reachable through the tunnel even
        // when it lives on a private address such as AdGuard on the VPS.
        for (server in dnsServers) {
            val clean = server.trim()
            if (clean.isEmpty()) continue
            val prefix = if (clean.contains(":")) 128 else 32
            runCatching { builder.addRoute(clean, prefix) }
        }
    }
}
