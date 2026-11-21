package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRegistryTest {

    private fun pending(id: String, trustId: String, expires: Long, notifId: Int = 1) =
        PendingApproval(
            requestId = id,
            requesterId = "reqdev-1-abcd",
            requesterName = "Laptop",
            trustId = trustId,
            expiresTs = expires,
            grant = "00",
            notifId = notifId,
        )

    @Test
    fun addAndTakeIsExactlyOnce() {
        val registry = ApprovalRegistry()
        registry.add(pending("req-1", "desktop-1", 1000))
        assertNotNull(registry.take("req-1"))
        assertNull(registry.take("req-1"))
    }

    @Test
    fun expiredEntriesAreRemoved() {
        val registry = ApprovalRegistry()
        registry.add(pending("req-1", "desktop-1", 100))
        registry.add(pending("req-2", "desktop-1", 200))
        val expired = registry.removeExpired(150)
        assertEquals(listOf("req-1"), expired.map { it.requestId })
        assertEquals(1, registry.size())
    }

    @Test
    fun activeCheckUsesTheOriginAndExpiry() {
        val registry = ApprovalRegistry()
        registry.add(pending("req-1", "desktop-1", 100))
        assertTrue(registry.hasActiveFor("desktop-1", 50))
        assertTrue(!registry.hasActiveFor("desktop-1", 100))
        assertTrue(!registry.hasActiveFor("desktop-2", 50))
    }

    @Test
    fun capacityEvictsTheOldest() {
        val registry = ApprovalRegistry(capacity = 2)
        registry.add(pending("req-1", "desktop-1", 100))
        registry.add(pending("req-2", "desktop-1", 100))
        val evicted = registry.add(pending("req-3", "desktop-1", 100))
        assertEquals("req-1", evicted?.requestId)
        assertEquals(2, registry.size())
        assertNull(registry.take("req-1"))
        assertNotNull(registry.take("req-3"))
    }
}
