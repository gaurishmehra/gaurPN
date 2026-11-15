package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundPolicyTest {

    private val selfId = "self-1"
    private val trusted = setOf("friend-1", "friend-2")

    private data class Case(
        val name: String,
        val kind: InboundKind,
        val senderId: String,
        val selfId: String = "self-1",
        val trustedIds: Set<String> = emptySet(),
        val onlyTrusted: Boolean = false,
        val expected: Boolean,
    )

    private fun cases(kind: InboundKind): List<Case> = listOf(
        Case("self reject ($kind)", kind = kind, senderId = "self-1", expected = false),
        Case(
            "untrusted + onlyTrusted reject ($kind)",
            kind = kind, senderId = "stranger", trustedIds = trusted, onlyTrusted = true,
            expected = false,
        ),
        Case(
            "untrusted + open accept ($kind)",
            kind = kind, senderId = "stranger", onlyTrusted = false,
            expected = true,
        ),
        Case(
            "trusted + onlyTrusted accept ($kind)",
            kind = kind, senderId = "friend-1", trustedIds = trusted, onlyTrusted = true,
            expected = true,
        ),
        Case("blank sender reject ($kind)", kind = kind, senderId = "", expected = false),
        Case("blank sender + onlyTrusted reject ($kind)", kind = kind, senderId = "  ", onlyTrusted = true, expected = false),
    )

    @Test
    fun everyKindCoversAllRules() {
        val all = InboundKind.entries.flatMap { cases(it) }
        all.forEach { case ->
            val actual = InboundPolicy.shouldAccept(
                case.kind, case.senderId, case.selfId, case.trustedIds, case.onlyTrusted,
            )
            val message = "${case.name}: expected ${case.expected}, got $actual"
            if (case.expected) assertTrue(message, actual) else assertFalse(message, actual)
        }
    }

    @Test
    fun trustedListIsIgnoredWhenOnlyTrustedIsOff() {
        assertTrue(
            InboundPolicy.shouldAccept(InboundKind.CLIPBOARD, "stranger", selfId, emptySet(), false),
        )
    }

    @Test
    fun trustDoesNotSurviveRevocation() {
        assertFalse(
            InboundPolicy.shouldAccept(
                InboundKind.NOTIFICATION_ACTION, "friend-1", selfId, emptySet(), true,
            ),
        )
    }

    /**
     * Requests are gated exactly like offers: an untrusted requester is
     * declined by the caller, never served. The generic table above already
     * covers REQUEST through [InboundKind.entries]; these cases pin the
     * intent so a future enum edit cannot quietly drop it.
     */
    @Test
    fun requestKindUsesTheSameTrustRulesAsOffers() {
        assertTrue(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, "friend-1", selfId, trusted, true),
        )
        assertFalse(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, "stranger", selfId, trusted, true),
        )
        assertFalse(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, selfId, selfId, trusted, false),
        )
        assertTrue(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, "stranger", selfId, emptySet(), false),
        )
        assertFalse(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, "", selfId, emptySet(), false),
        )
    }

    /**
     * The v3.2 trust fallback: a desktop requester connects under an
     * ephemeral reqdev- id and carries its stable device id in origin_id.
     * The gate must be handed the stable id, or an only-trusted target
     * declines every desktop request. [RequestEnvelope.trustId] performs the
     * fallback; these cases pin the accept/reject outcome it feeds.
     */
    @Test
    fun requestTrustUsesTheStableOriginId() {
        val ephemeral = "reqdev-4242-a1b2"
        val stable = "laptop-1"
        val trusted = setOf(stable)
        assertFalse(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, ephemeral, selfId, trusted, true),
        )
        assertTrue(
            InboundPolicy.shouldAccept(InboundKind.REQUEST, stable, selfId, trusted, true),
        )
    }

    /**
     * Browse and fetch are a consent capability, not the generic gate: the
     * target serves them only to an explicitly trusted device, whatever the
     * only-trusted toggle says. The pure rule has no toggle input on purpose.
     */
    @Test
    fun browseRequiresExplicitTrustEvenWithOnlyTrustedOff() {
        // The generic gate accepts a stranger while the toggle is off...
        assertTrue(
            InboundPolicy.shouldAccept(
                InboundKind.CLIPBOARD, "stranger", selfId, trusted, onlyTrusted = false,
            ),
        )
        // ...but browse/fetch is a consent capability and still denies. The
        // pure rule has no toggle input on purpose.
        assertFalse(InboundPolicy.shouldServeBrowse("stranger", selfId, trusted))
        assertFalse(InboundPolicy.shouldServeBrowse("stranger", selfId, emptySet()))
        assertTrue(InboundPolicy.shouldServeBrowse("friend-1", selfId, trusted))
        assertFalse(InboundPolicy.shouldServeBrowse(selfId, selfId, trusted))
        assertFalse(InboundPolicy.shouldServeBrowse("", selfId, trusted))
        assertFalse(InboundPolicy.shouldServeBrowse("  ", selfId, trusted))
    }

    /**
     * The claimed origin id decides: a desktop asks under an ephemeral
     * reqdev- id and carries its stable id in origin_id. The trust list holds
     * the stable id, so the fallback is what makes the check meaningful.
     */
    @Test
    fun browseTrustUsesTheClaimedOriginId() {
        val browse = BrowseEnvelope(
            requestId = "req-0011223344556677",
            requesterId = "reqdev-4242-a1b2",
            originId = "laptop-1",
            requesterName = "Laptop",
            targetId = selfId,
            path = "",
            ts = 1L,
        )
        assertTrue(
            InboundPolicy.shouldServeBrowse(browse.trustId, selfId, setOf("laptop-1")),
        )
        assertFalse(
            InboundPolicy.shouldServeBrowse(browse.requesterId, selfId, setOf("laptop-1")),
        )
    }
}
