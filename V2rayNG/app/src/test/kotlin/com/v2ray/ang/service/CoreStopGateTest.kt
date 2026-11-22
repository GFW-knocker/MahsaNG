package com.v2ray.ang.service

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CoreStopGateTest {
    @Test fun stopBlocksStartUntilNativeCleanupCompletes() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertFalse(gate.requestStart())
        assertFalse(gate.canResume(token))
        val restart = gate.completeStop(token, success = true)!!
        assertTrue(gate.canResume(restart))
        assertTrue(gate.requestStart())
    }

    @Test fun stoppingStateRemainsVisibleUntilAsyncStopCompletes() {
        val gate = CoreStopGate()
        assertFalse(gate.isStopping())
        val token = gate.beginStop()!!
        assertTrue(gate.isStopping())
        assertEquals(null, gate.beginStop())
        assertTrue(gate.isStopping())
        gate.completeStop(token, success = true)
        assertFalse(gate.isStopping())
    }

    @Test fun duplicateNativeStopPreservesDeferredRestartWithoutSchedulingSecondStop() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertFalse(gate.requestStart())
        assertEquals(null, gate.beginStop())
        assertTrue(gate.completeStop(token, success = true) != null)
    }

    @Test fun nativeStopFailureQuarantinesAllFutureStarts() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertFalse(gate.requestStart())
        assertEquals(null, gate.completeStop(token, success = false))
        // A failed stopLoop is not native termination evidence. No new core
        // may start while the old one may still own sockets or a TUN.
        assertFalse(gate.requestStart())
        assertFalse(gate.requestStart())
        assertTrue(gate.isStopping())
    }

    @Test fun failedStopCannotBeRetriedOrReleasedByAStaleCompletion() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertEquals(null, gate.completeStop(token, success = false))
        assertEquals(null, gate.beginStop())
        assertEquals(null, gate.completeStop(token, success = true))
        assertFalse(gate.canResume(token))
        assertFalse(gate.requestStart())
    }

    @Test fun failedStopWithoutPendingStartStillBlocksLaterStartup() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertEquals(null, gate.completeStop(token, success = false))
        assertTrue(gate.isStopping())
        assertFalse(gate.requestStart())
    }

    @Test fun successfulStopStillPermitsFreshStartsWithoutDeferral() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertEquals(null, gate.completeStop(token, success = true))
        assertFalse(gate.isStopping())
        assertTrue(gate.requestStart())
    }

    @Test fun newStopInvalidatesPreviouslyQueuedRestart() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertFalse(gate.requestStart())
        val restartToken = gate.completeStop(token, success = true)!!
        assertEquals(null, gate.beginStop()?.let { second ->
            assertFalse(gate.canResume(restartToken))
            gate.completeStop(second, success = true)
        })
        assertFalse(gate.canResume(restartToken))
    }

    @Test fun oldCompletionIsIgnoredAfterSuccess() {
        val gate = CoreStopGate()
        val token = gate.beginStop()!!
        assertEquals(null, gate.completeStop(token, success = true))
        assertEquals(null, gate.completeStop(token, success = true))
        assertTrue(gate.requestStart())
        assertFalse(gate.isQuarantined())
    }

    @Test fun isQuarantinedReflectsUnverifiedNativeStop() {
        val gate = CoreStopGate()
        assertFalse(gate.isQuarantined())
        val token = gate.beginStop()!!
        assertFalse(gate.isQuarantined())
        assertEquals(null, gate.completeStop(token, success = false))
        assertTrue(gate.isQuarantined())
    }
}
