package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class CandidateStabilityTest {
    private val now = 2_000_000_000_000L
    private val candidate = TargetDiscoveryPlanner.plan("198.51.100.7", 443, true, "example.org").single()

    private fun observed(
        pass: Int,
        state: TargetProbeStatus,
        route: TargetProbeRoute = TargetProbeRoute.LOCAL_HTTP_CONNECT,
        target: TargetDiscoveryTarget = candidate,
        at: Long = now,
        time: Long? = 50L,
    ) = TargetDiscoveryResult(target, state, time, time, at - 100L, at, route, pass)

    @Test fun threeVerifiedProxyHandshakeRunsQualifyAsRepeatedTransportEvidence() {
        val rows = listOf(
            observed(1, TargetProbeStatus.TLS_VERIFIED, at = now - 3000, time = 90L),
            observed(2, TargetProbeStatus.TLS_VERIFIED, at = now - 2000, time = 30L),
            observed(3, TargetProbeStatus.TLS_VERIFIED, at = now - 1000, time = 50L),
        )
        val summary = CandidateStability.summarize(rows, 3, now).single()
        assertEquals(3, summary.attempts)
        assertEquals(3, summary.successes)
        assertEquals(3, summary.verifiedTls)
        assertEquals(50L, summary.medianMs)
        assertTrue(summary.completed)
        assertTrue(summary.stableProxyTls)
        assertEquals(TargetProbeRoute.LOCAL_HTTP_CONNECT, summary.route)
    }

    @Test fun aSingleProxyConnectResponseIsNotVerifiedTunnelConnectivity() {
        val result = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.PROXY_CONNECT_ESTABLISHED)), 1, now
        ).single()
        assertEquals(1, result.successes)
        assertEquals(0, result.verifiedTls)
        assertFalse(result.stableProxyTls)
    }

    @Test fun directTlsEvidenceNeverQualifiesAsProxyVerifiedCandidate() {
        val result = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED, TargetProbeRoute.APP_NETWORK),
                observed(2, TargetProbeStatus.TLS_VERIFIED, TargetProbeRoute.APP_NETWORK)), 2, now
        ).single()
        assertFalse(result.stableProxyTls)
        assertEquals(TargetProbeRoute.APP_NETWORK, result.route)
    }

    @Test fun proxyAndDirectResultsDoNotGetCombinedIntoOneCandidate() {
        val results = listOf(
            observed(1, TargetProbeStatus.TLS_VERIFIED, TargetProbeRoute.LOCAL_HTTP_CONNECT),
            observed(1, TargetProbeStatus.TLS_VERIFIED, TargetProbeRoute.APP_NETWORK),
        )
        val summaries = CandidateStability.summarize(results, 1, now)
        assertEquals(2, summaries.size)
        assertTrue(summaries.all { it.attempts == 1 })
    }

    @Test fun missingOrFailedPassIsNotCountedAsReliable() {
        val missing = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED)), 3, now).single()
        assertEquals(1, missing.attempts)
        assertFalse(missing.completed)
        assertFalse(missing.stableProxyTls)

        val withFailure = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED),
                observed(2, TargetProbeStatus.PROXY_CONNECT_REJECTED)), 2, now).single()
        assertTrue(withFailure.completed)
        assertEquals(1, withFailure.successes)
        assertFalse(withFailure.stableProxyTls)
    }

    @Test fun duplicatePassCannotInflateSuccessRatio() {
        val results = listOf(
            observed(1, TargetProbeStatus.TLS_VERIFIED, at = now - 3),
            observed(1, TargetProbeStatus.TLS_VERIFIED, at = now - 2),
            observed(2, TargetProbeStatus.PROXY_CONNECT_REJECTED, at = now - 1),
        )
        val summary = CandidateStability.summarize(results, 2, now).single()
        assertEquals(2, summary.attempts)
        assertEquals(1, summary.successes)
    }

    @Test fun staleAndFutureEvidenceCannotClaimCurrentStability() {
        val stale = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED,
                at = now - CandidateStability.MAX_EVIDENCE_AGE_MS - 1)), 1, now)
        assertTrue(stale.isEmpty())
        val future = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED, at = now + 1)), 1, now)
        assertTrue(future.isEmpty())
    }

    @Test fun onlyDistinctSniValuesGetDistinctCandidateGroups() {
        val other = candidate.copy(sni = "another.example")
        val result = CandidateStability.summarize(
            listOf(observed(1, TargetProbeStatus.TLS_VERIFIED),
                observed(2, TargetProbeStatus.TLS_VERIFIED, target = other)), 2, now)
        assertEquals(2, result.size)
        assertTrue(result.none { it.stableProxyTls })
    }

    @Test fun runPlannerBoundsRepeatedProbesAndRefusesSilentTruncation() {
        val targets = TargetDiscoveryPlanner.plan("203.0.113.0/25", 443, false)
        val plan = CandidateProbeRunPlan.create(targets, 3)
        assertEquals(384, plan.size)
        assertEquals(1, plan.first().passIndex)
        assertEquals(3, plan.last().passIndex)
        assertEquals(128, plan.count { it.passIndex == 2 })
        for (bad in listOf(0, 4, 100)) {
            assertThrows(IllegalArgumentException::class.java) {
                CandidateProbeRunPlan.create(targets, bad)
            }
        }
    }
}
