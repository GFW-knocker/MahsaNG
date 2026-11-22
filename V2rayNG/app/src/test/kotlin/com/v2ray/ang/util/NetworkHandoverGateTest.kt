package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkHandoverGateTest {
    @Test fun aToBToAIsStillAnObsoleteRun() {
        val gate = NetworkHandoverGate("wifi-A")
        assertTrue(gate.accepts("wifi-A"))
        gate.onAvailable("cellular-B")
        assertFalse(gate.accepts("cellular-B"))
        gate.onAvailable("wifi-A")
        assertFalse(gate.accepts("wifi-A"))
    }

    @Test fun delayedLossOfOriginalNetworkInvalidatesTheRun() {
        val gate = NetworkHandoverGate("wifi-A")
        gate.onAvailable("wifi-A")
        assertTrue(gate.accepts("wifi-A"))
        gate.onLost("wifi-A")
        assertFalse(gate.accepts("wifi-A"))
    }

    @Test fun unrelatedLossDoesNotMaskStillCurrentNetwork() {
        val gate = NetworkHandoverGate("wifi-A")
        gate.onLost("old-cellular")
        assertTrue(gate.accepts("wifi-A"))
        assertFalse(gate.accepts("old-cellular"))
    }

    @Test fun absentNetworkOrRegistrationFailureCannotAccept() {
        val missing = NetworkHandoverGate<String>(null)
        assertFalse(missing.accepts("wifi-A"))
        val failedRegistration = NetworkHandoverGate("wifi-A")
        failedRegistration.invalidate()
        assertFalse(failedRegistration.accepts("wifi-A"))
        failedRegistration.onAvailable("wifi-A")
        assertFalse(failedRegistration.accepts("wifi-A"))
    }

    @Test fun callbackFromAnotherThreadCannotReviveOriginalNetwork() {
        val gate = NetworkHandoverGate("wifi-A")
        val done = CountDownLatch(1)
        val worker = Thread {
            gate.onAvailable("cellular-B")
            gate.onAvailable("wifi-A")
            done.countDown()
        }
        worker.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(gate.accepts("wifi-A"))
        worker.join()
    }
}
