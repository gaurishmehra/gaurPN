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
}
