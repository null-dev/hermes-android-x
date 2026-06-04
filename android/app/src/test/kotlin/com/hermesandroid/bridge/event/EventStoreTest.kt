package com.hermesandroid.bridge.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class EventStoreTest {
    @Test fun appendAssignsMonotonicSeq() {
        val s = EventStore(capacity = 10)
        val a = s.append(EventRecord(0, 1L, "x", "p", "t1"))
        val b = s.append(EventRecord(0, 1L, "x", "p", "t2"))
        assertTrue(b.seq > a.seq)
    }

    @Test fun sinceReturnsOnlyNewer() {
        val s = EventStore(capacity = 10)
        val first = s.append(EventRecord(0, 1L, "x", "p", "a"))
        s.append(EventRecord(0, 1L, "x", "p", "b"))
        val newer = s.since(first.seq)
        assertEquals(listOf("b"), newer.map { it.text })
    }

    @Test fun capacityIsBounded() {
        val s = EventStore(capacity = 5)
        repeat(100) { s.append(EventRecord(0, 1L, "x", "p", "t$it")) }
        assertEquals(5, s.snapshot().size)
        assertEquals("t99", s.snapshot().last().text) // newest retained
    }

    @Test fun concurrentWritersRespectCapacityAndDontLose() {
        val s = EventStore(capacity = 200)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        repeat(8) { w ->
            pool.execute {
                repeat(500) { s.append(EventRecord(0, 1L, "x", "p", "w$w-$it")) }
                latch.countDown()
            }
        }
        latch.await()
        pool.shutdown()
        // Never exceeds capacity; seqs are unique and strictly increasing in insertion order.
        assertTrue(s.snapshot().size <= 200)
        val seqs = s.snapshot().map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.toSet().size, seqs.size)
    }
}
