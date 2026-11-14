package dev.gaurish.outlinelite.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TransferRegistryTest {

    @Test
    fun cancelInvokesHandleOnceAndRemovesEntry() {
        val registry = TransferRegistry()
        val calls = AtomicInteger(0)
        registry.register("tx-1") { calls.incrementAndGet() }
        registry.cancel("tx-1")
        registry.cancel("tx-1") // a second cancel is a safe no-op
        assertEquals(1, calls.get())
        assertTrue(registry.activeIds().isEmpty())
    }

    @Test
    fun removeUnregistersWithoutCancelling() {
        val registry = TransferRegistry()
        val calls = AtomicInteger(0)
        registry.register("tx-1") { calls.incrementAndGet() }
        registry.remove("tx-1")
        assertEquals(0, calls.get())
        registry.cancelAll() // nothing left to cancel
        assertEquals(0, calls.get())
        assertTrue(registry.activeIds().isEmpty())
    }

    @Test
    fun cancelAllInvokesEveryHandleOnce() {
        val registry = TransferRegistry()
        val invoked = ConcurrentHashMap.newKeySet<String>()
        for (i in 0 until 10) registry.register("tx-$i") { invoked.add("tx-$i") }
        registry.cancelAll()
        assertEquals((0 until 10).map { "tx-$it" }.toSet(), invoked)
        assertTrue(registry.activeIds().isEmpty())
    }

    @Test
    fun registerDisplacesAndCancelsPreviousAttempt() {
        val registry = TransferRegistry()
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        registry.register("tx-1") { first.incrementAndGet() }
        registry.register("tx-1") { second.incrementAndGet() } // supersede
        assertEquals(1, first.get()) // displaced attempt cancelled exactly once
        assertEquals(0, second.get())
        registry.cancel("tx-1")
        assertEquals(1, second.get())
        assertEquals(1, first.get()) // still exactly once
    }

    @Test
    fun registrationRemoveOnlyDropsItsOwnEntry() {
        val registry = TransferRegistry()
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        val firstReg = registry.register("tx-1") { first.incrementAndGet() }
        registry.register("tx-1") { second.incrementAndGet() } // displaces + cancels first
        firstReg.remove() // stale completion — must NOT drop the live registration
        assertEquals(listOf("tx-1"), registry.activeIds())
        registry.cancel("tx-1")
        assertEquals(1, second.get())
    }

    @Test
    fun concurrentRegisterCancelAndRemovePreserveExactlyOnce() {
        val registry = TransferRegistry()
        val invocations = ConcurrentHashMap<String, AtomicInteger>()
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(250) { i ->
                    val id = "tx-$t-$i"
                    val counter = AtomicInteger(0)
                    invocations[id] = counter
                    registry.register(id) { counter.incrementAndGet() }
                    if (i % 3 == 0) registry.cancel(id) else registry.remove(id)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        registry.cancelAll()
        invocations.values.forEach { c ->
            assertTrue("cancel handle invoked ${c.get()} times", c.get() <= 1)
        }
        assertTrue(registry.activeIds().isEmpty())
    }

    @Test
    fun concurrentCancelAllIsSafe() {
        val registry = TransferRegistry()
        val invocations = ConcurrentHashMap<String, AtomicInteger>()
        val stop = AtomicBoolean(false)
        val canceller = Thread {
            while (!stop.get()) {
                registry.cancelAll()
            }
        }
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(500) { i ->
                    val id = "tx-$t-$i"
                    val counter = AtomicInteger(0)
                    invocations[id] = counter
                    registry.register(id) { counter.incrementAndGet() }
                    if (i % 2 == 0) registry.cancel(id)
                }
            }
        }
        canceller.start()
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        stop.set(true)
        canceller.join()
        invocations.values.forEach { c ->
            assertTrue("cancel handle invoked ${c.get()} times", c.get() <= 1)
        }
    }
}
