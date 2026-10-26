package dev.gaurish.outlinelite.tunnel

import android.net.VpnService

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
 */
object LanBypassRoutes {

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

    fun applyRoutes(builder: VpnService.Builder, allowLanAccess: Boolean) {
        if (!allowLanAccess) {
            builder.addRoute("0.0.0.0", 0)
        } else {
            for ((prefix, length) in EXCLUDE_LAN_IPV4_ROUTES) {
                builder.addRoute(prefix, length)
            }
        }
        builder.addRoute("::", 0)
    }
}
