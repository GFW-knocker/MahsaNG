package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TcpProbePresetTest {
    @Test fun missingOrUnexpectedPreferenceUsesBalancedLimits() {
        assertEquals(TcpProbePreset.BALANCED, TcpProbePreset.fromStored(null))
        assertEquals(TcpProbePreset.BALANCED, TcpProbePreset.fromStored("bogus"))
        assertEquals(TcpProbePreset.BALANCED, TcpProbePreset.fromStored("500000"))
    }

    @Test fun quickIsOneShortTcpConnectAttempt() {
        var calls = 0
        val result = TcpProbePreset.fromStored("quick").probe({ false }) {
            calls++
            assertEquals(1_500, it)
            -1L
        }
        assertEquals(-1L, result)
        assertEquals(1, calls)
    }

    @Test fun balancedStopsAfterFirstSuccessfulConnect() {
        var calls = 0
        val result = TcpProbePreset.BALANCED.probe({ false }) {
            calls++
            assertEquals(3_000, it)
            28L
        }
        assertEquals(28L, result)
        assertEquals(1, calls)
    }

    @Test fun balancedRetriesOnlyWhenFirstConnectFailed() {
        var calls = 0
        val result = TcpProbePreset.BALANCED.probe({ false }) { calls++; -1L }
        assertEquals(-1L, result)
        assertEquals(2, calls)
    }

    @Test fun diagnosticHasHardAttemptAndTimeoutCaps() {
        var calls = 0
        val result = TcpProbePreset.DIAGNOSTIC.probe({ false }) {
            calls++
            assertEquals(5_000, it)
            -1L
        }
        assertEquals(-1L, result)
        assertEquals(3, calls)
    }

    @Test fun cancellationAfterConnectRejectsLateSuccessAndRetry() {
        var calls = 0
        var cancelled = false
        val result = TcpProbePreset.DIAGNOSTIC.probe({ cancelled }) {
            calls++
            cancelled = true
            33L
        }
        assertEquals(-1L, result)
        assertEquals(1, calls)
    }

    @Test fun cancellationBeforeConnectSkipsAllNetworkWork() {
        var calls = 0
        val result = TcpProbePreset.QUICK.probe({ true }) { calls++; 2L }
        assertEquals(-1L, result)
        assertEquals(0, calls)
    }
}
