package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class TcpProbeEvidenceTest {
    @Test fun firstTcpSuccessRetainsOriginalDelayAndSingleAttempt() {
        val evidence = TcpProbeEvidenceRunner.measure(TcpProbePreset.BALANCED, { false }) {
            assertEquals(3000, it)
            52L to null
        }
        assertEquals(52L, evidence.delayMs)
        assertEquals(1, evidence.attempts.size)
        assertTrue(evidence.attempts.single().succeeded)
        assertTrue(evidence.totalElapsedMs >= 1)
    }

    @Test fun failedSocketsKeepSafeReasonAndOriginalBoundedRetryCount() {
        var calls = 0
        val evidence = TcpProbeEvidenceRunner.measure(TcpProbePreset.BALANCED, { false }) {
            calls++
            -1L to SafeProbeFailure.Kind.TIMEOUT
        }
        assertEquals(-1L, evidence.delayMs)
        assertEquals(2, calls)
        assertEquals(2, evidence.attempts.size)
        assertTrue(evidence.attempts.all { it.failure == SafeProbeFailure.Kind.TIMEOUT })
    }

    @Test fun cancelledInFlightSuccessCannotPublishAPositiveDelayOrRetry() {
        var cancelled = false
        val evidence = TcpProbeEvidenceRunner.measure(TcpProbePreset.DIAGNOSTIC, { cancelled }) {
            cancelled = true
            33L to null
        }
        assertEquals(-1L, evidence.delayMs)
        assertEquals(1, evidence.attempts.size)
    }
}
