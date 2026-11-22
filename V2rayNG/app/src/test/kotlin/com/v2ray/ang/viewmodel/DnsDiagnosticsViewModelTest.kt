package com.v2ray.ang.viewmodel

import com.v2ray.ang.util.dns.DnsProbeResult
import com.v2ray.ang.util.dns.DnsRoute
import com.v2ray.ang.util.dns.DnsType
import org.junit.Assert.*
import org.junit.Test

class DnsDiagnosticsViewModelTest {
    @Test fun activityRecreationRetainsCompletedDnsEvidenceButStopsOldGeneration() {
        val state = DnsDiagnosticsViewModel()
        val observation = DnsProbeResult("192.0.2.53:53", "example.com", DnsType.A,
            "TIMEOUT", 100, null, emptyList())
        state.results.add(observation)
        state.completed = 1
        state.total = 3
        state.activeRun = true
        state.generation = 4
        state.selectedRoute = DnsRoute.LOCAL_SOCKS5_TCP
        state.filter = 4
        state.interruptActiveRun()
        assertEquals(listOf(observation), state.results)
        assertEquals(1, state.completed)
        assertEquals(3, state.total)
        assertTrue(state.wasStopped)
        assertFalse(state.activeRun)
        assertEquals(5L, state.generation)
        assertEquals(4, state.filter)
        assertEquals(DnsRoute.LOCAL_SOCKS5_TCP, state.selectedRoute)
    }
}
