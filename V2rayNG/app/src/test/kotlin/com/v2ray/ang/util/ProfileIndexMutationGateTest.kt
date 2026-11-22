package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ProfileIndexMutationGateTest {
    @Test fun failedNonblockingLeaseDoesNotRunAnyMutationOrUnlock() {
        var mutations = 0
        var unlocks = 0
        val result = ProfileIndexMutationGate.execute(
            unavailable = "BUSY", tryAcquire = { false }, release = { unlocks++ }) {
            mutations++
            "WRITTEN"
        }
        assertEquals("BUSY", result)
        assertEquals(0, mutations)
        assertEquals(0, unlocks)
    }

    @Test fun releaseIsGuaranteedAfterNormalAndExceptionalExecution() {
        var acquired = 0
        var released = 0
        val result = ProfileIndexMutationGate.execute(
            unavailable = -1,
            tryAcquire = { acquired++; true },
            release = { released++ },
        ) { 42 }
        assertEquals(42, result)
        try {
            ProfileIndexMutationGate.execute(
                unavailable = -1,
                tryAcquire = { acquired++; true },
                release = { released++ },
            ) { error("operation failed") }
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertEquals("operation failed", error.message)
        }
        assertEquals(2, acquired)
        assertEquals(2, released)
    }

    @Test fun nonlocalReturnStillReleasesNativeLease() {
        var unlocked = false
        fun run(): String {
            return ProfileIndexMutationGate.execute(
                unavailable = "BUSY",
                tryAcquire = { true },
                release = { unlocked = true },
            ) {
                return "COMMITTED" // Nonlocal Kotlin return from an inline critical section.
            }
        }
        assertEquals("COMMITTED", run())
        assertTrue(unlocked)
    }

    @Test fun heldLeaseRefusesAnotherWriterUntilItIsReleased() {
        val locked = AtomicBoolean(false)
        val lease = { locked.compareAndSet(false, true) }
        val release = { locked.set(false) }
        var secondEntered = false
        val first = ProfileIndexMutationGate.execute(-1, lease, release) {
            assertTrue(locked.get())
            val rejected = ProfileIndexMutationGate.execute(-1, lease, release) {
                secondEntered = true
                99
            }
            assertEquals(-1, rejected)
            17
        }
        assertEquals(17, first)
        assertFalse(secondEntered)
        assertFalse(locked.get())
    }
}
