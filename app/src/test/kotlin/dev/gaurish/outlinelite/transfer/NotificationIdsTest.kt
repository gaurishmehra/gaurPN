package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    // The classic String.hashCode collision pair: "Aa" and "BB" both hash to 2112.

    @Test
    fun collidingTransferIdsGetDistinctIds() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val a = NotificationIds.acquire("Aa", sending = false)
        try {
            val b = NotificationIds.acquire("BB", sending = false)
            try {
                assertNotEquals(a, b)
                assertEquals(a + 1, b) // collision resolved by probing one slot up
            } finally {
                NotificationIds.release(b)
            }
        } finally {
            NotificationIds.release(a)
        }
    }

    @Test
    fun releaseMakesSlotReusable() {
        val first = NotificationIds.acquire("Aa", sending = true)
        NotificationIds.release(first)
        val second = NotificationIds.acquire("BB", sending = true)
        try {
            assertEquals(first, second)
        } finally {
            NotificationIds.release(second)
        }
    }

    @Test
    fun sendAndReceiveBasesDoNotOverlap() {
        val sendId = NotificationIds.acquire("Aa", sending = true)
        val receiveId = NotificationIds.acquire("Aa", sending = false)
        try {
            assertNotEquals(sendId, receiveId)
            assertEquals(NotificationIds.SEND_BASE + "Aa".hashCode(), sendId)
            assertEquals(NotificationIds.RECEIVE_BASE + "Aa".hashCode(), receiveId)
        } finally {
            NotificationIds.release(sendId)
            NotificationIds.release(receiveId)
        }
    }

    @Test
    fun manyConcurrentTransfersNeverShareIds() {
        val ids = HashSet<Int>()
        val acquired = mutableListOf<Int>()
        try {
            repeat(256) { i ->
                val id = NotificationIds.acquire("transfer-$i", sending = i % 2 == 0)
                acquired.add(id)
                assertTrue("duplicate id $id for transfer-$i", ids.add(id))
            }
        } finally {
            acquired.forEach { NotificationIds.release(it) }
        }
    }

    @Test
    fun probeWrapsWithinTheBaseRange() {
        // Hold the preferred slot, then force a wrap by holding many sequential
        // slots after it; the acquired id must stay within the base's range.
        val held = mutableListOf<Int>()
        try {
            repeat(64) {
                held += NotificationIds.acquire("wrap-$it", sending = false)
            }
            held.forEach { assertTrue(it >= NotificationIds.RECEIVE_BASE) }
            held.forEach { assertTrue(it <= NotificationIds.RECEIVE_BASE + 0xFFFFFF) }
        } finally {
            held.forEach { NotificationIds.release(it) }
        }
    }
}
