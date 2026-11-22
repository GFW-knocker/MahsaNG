package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class BoundedProbeQueueTest {
    @Test fun largeSubscriptionStartsAtMostSixteenWorkers() {
        val queue = BoundedProbeQueue((1..5000).toList(), concurrency = 5000)
        assertEquals(16, queue.workerCount)
    }

    @Test fun smallerSelectionStartsNoIdleWorkers() {
        assertEquals(2, BoundedProbeQueue(listOf("a", "b"), 8).workerCount)
        assertEquals(0, BoundedProbeQueue(emptyList<Int>(), 8).workerCount)
    }

    @Test fun invalidStoredSettingsUseConservativeDefault() {
        assertEquals(8, BoundedProbeQueue.concurrencyFromStored(null))
        assertEquals(8, BoundedProbeQueue.concurrencyFromStored("not-a-number"))
        assertEquals(1, BoundedProbeQueue.concurrencyFromStored("0"))
        assertEquals(16, BoundedProbeQueue.concurrencyFromStored("999"))
        assertEquals(4, BoundedProbeQueue.concurrencyFromStored("4"))
    }

    @Test fun originalListCanChangeWithoutChangingQueuedTargets() {
        val original = mutableListOf("old", "other")
        val queue = BoundedProbeQueue(original, 2)
        original.clear()
        original += "replacement"
        assertEquals("old", queue.claim())
        assertEquals("other", queue.claim())
        assertEquals(null, queue.claim())
    }

    @Test fun concurrentWorkersClaimEachTargetExactlyOnce() {
        val queue = BoundedProbeQueue((0 until 1000).toList(), 8)
        val claimed = Collections.synchronizedSet(mutableSetOf<Int>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (0 until queue.workerCount).map {
            Thread {
                try {
                    while (true) {
                        val item = queue.claim() ?: break
                        check(claimed.add(item)) { "duplicate target: $item" }
                    }
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
        }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        // Thread.join does not propagate worker exceptions to JUnit.
        assertTrue("Worker failures: " + failures, failures.isEmpty())
        assertEquals(1000, claimed.size)
        assertTrue(claimed.contains(999))
    }
}
