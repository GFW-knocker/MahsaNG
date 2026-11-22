package com.v2ray.ang.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The real singleton gate is tested; these are not native JNI receipts. */
class PsiphonNativeWorkerGateTest {
    @Test fun stopBeforeNativeEntryIsNotLost() {
        val stopped = AtomicBoolean(true)
        val first = requireNotNull(PsiphonNativeWorkerGate.acquire { stopped.set(false) })
        try {
            stopped.set(true)
            assertNull(PsiphonNativeWorkerGate.acquire { stopped.set(false) })
            assertTrue(stopped.get())
        } finally {
            assertTrue(PsiphonNativeWorkerGate.release(first))
        }
        val second = requireNotNull(PsiphonNativeWorkerGate.acquire { stopped.set(false) })
        try {
            assertFalse(stopped.get())
        } finally {
            assertTrue(PsiphonNativeWorkerGate.release(second))
        }
    }

    @Test fun nativePrepareFailureDoesNotRetainLease() {
        try {
            PsiphonNativeWorkerGate.acquire { error("JNI unavailable") }
            fail("expected JNI failure")
        } catch (_: IllegalStateException) { }
        val next = requireNotNull(PsiphonNativeWorkerGate.acquire { })
        assertTrue(PsiphonNativeWorkerGate.release(next))
    }

    @Test fun unrelatedThreadCannotReleaseAnotherNativeWorker() {
        val active = requireNotNull(PsiphonNativeWorkerGate.acquire { })
        val fake = PsiphonNativeWorkerGate.Lease() // Not the issued instance.
        val completed = CountDownLatch(1)
        val incorrectlyReleased = AtomicBoolean(true)
        try {
            val unrelated = Thread {
                try {
                    incorrectlyReleased.set(PsiphonNativeWorkerGate.release(fake))
                } finally {
                    completed.countDown()
                }
            }
            unrelated.start()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            unrelated.join(2_000)
            assertFalse(incorrectlyReleased.get())
            assertNull("Unrelated callback admitted overlapping JNI owner",
                PsiphonNativeWorkerGate.acquire { })
        } finally {
            assertTrue(PsiphonNativeWorkerGate.release(active))
        }
    }

    @Test fun duplicateReleaseMustNotClearSuccessorNativeWorker() {
        val earlier = requireNotNull(PsiphonNativeWorkerGate.acquire { })
        assertTrue(PsiphonNativeWorkerGate.release(earlier))
        val successor = requireNotNull(PsiphonNativeWorkerGate.acquire { })
        try {
            assertFalse(PsiphonNativeWorkerGate.release(earlier))
            assertNull("Old completion opened a successor's JNI lease",
                PsiphonNativeWorkerGate.acquire { })
        } finally {
            assertTrue(PsiphonNativeWorkerGate.release(successor))
        }
        // The old lease remains invalid even after the new one exits.
        assertFalse(PsiphonNativeWorkerGate.release(earlier))
    }

    @Test fun rejectedAcquireNeverRearmsNativeTerminationFlag() {
        val prepareCalls = AtomicInteger()
        val active = requireNotNull(PsiphonNativeWorkerGate.acquire { prepareCalls.incrementAndGet() })
        try {
            assertNull(PsiphonNativeWorkerGate.acquire { prepareCalls.incrementAndGet() })
            assertEquals(1, prepareCalls.get())
        } finally {
            assertTrue(PsiphonNativeWorkerGate.release(active))
        }
    }

    @Test fun concurrentWorkerStartRequestsAreCoalesced() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val accepted = AtomicInteger()
        val owner = AtomicReference<PsiphonNativeWorkerGate.Lease?>()
        repeat(16) {
            Thread {
                try {
                    start.await()
                    val lease = PsiphonNativeWorkerGate.acquire { }
                    if (lease != null) {
                        accepted.incrementAndGet()
                        owner.set(lease)
                    }
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(1, accepted.get())
        } finally {
            owner.get()?.let { assertTrue(PsiphonNativeWorkerGate.release(it)) }
        }
    }
}
