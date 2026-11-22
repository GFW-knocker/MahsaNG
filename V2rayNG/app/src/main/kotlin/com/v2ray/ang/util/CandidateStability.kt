package com.v2ray.ang.util

/** A single explicit, numbered attempt. Never creates independent or unbounded worker jobs. */
internal data class CandidateProbeAttempt(val target: TargetDiscoveryTarget, val passIndex: Int)

/** Multipass planner; one pass is still useful diagnostic evidence, not stability proof. */
internal object CandidateProbeRunPlan {
    const val MAX_PASSES = 3
    const val MAX_ATTEMPTS = TargetDiscoveryPlanner.MAX_TARGETS * MAX_PASSES

    fun create(targets: List<TargetDiscoveryTarget>, passes: Int): List<CandidateProbeAttempt> {
        require(targets.isNotEmpty() && targets.size <= TargetDiscoveryPlanner.MAX_TARGETS)
        require(passes in 1..MAX_PASSES)
        require(targets.size.toLong() * passes <= MAX_ATTEMPTS)
        return (1..passes).flatMap { pass ->
            targets.map { target -> CandidateProbeAttempt(target, pass) }
        }
    }
}

/** Per-target, per-route evidence, never inferred as global device VPN health. */
internal data class CandidateStabilitySummary(
    val target: TargetDiscoveryTarget,
    val route: TargetProbeRoute,
    val attempts: Int,
    val expectedPasses: Int,
    val successes: Int,
    val verifiedTls: Int,
    val medianMs: Long?,
    val lastObservedAtMillis: Long,
    val completed: Boolean,
    val stableProxyTls: Boolean,
)

/**
 * Evidence from exactly this opt-in session. Does not persist or automatically
 * select VPN profiles. Failed or missing passes are never silently successful.
 */
internal object CandidateStability {
    const val MAX_EVIDENCE_AGE_MS = 10 * 60_000L

    fun summarize(
        observations: Collection<TargetDiscoveryResult>,
        expectedPasses: Int,
        nowMillis: Long,
    ): List<CandidateStabilitySummary> {
        require(expectedPasses in 1..CandidateProbeRunPlan.MAX_PASSES)
        if (nowMillis <= 0) return emptyList()

        val valid = observations.asSequence().filter {
            it.passIndex in 1..expectedPasses &&
                it.endedAtMillis in 1..nowMillis &&
                nowMillis - it.endedAtMillis <= MAX_EVIDENCE_AGE_MS
        }.toList()
        val groups = valid.groupBy { it.target to it.route }
        return groups.map { (key, results) ->
            // A duplicate event for the same attempt is one observation, never
            // two "successes" that inflate reliability.
            val latestPerPass = results.groupBy { it.passIndex }.values.map { samePass ->
                samePass.maxByOrNull { it.endedAtMillis }!!
            }
            val successful = latestPerPass.filter { it.succeeded }
            val verified = latestPerPass.count { it.verifiedTls }
            val latencies = successful.mapNotNull { observation ->
                (observation.tlsElapsedMs ?: observation.tcpElapsedMs)
                    ?.takeIf { it > 0 }
            }.sorted()
            val middle = if (latencies.isEmpty()) null
                else latencies[(latencies.size - 1) / 2]
            val complete = latestPerPass.size == expectedPasses
            CandidateStabilitySummary(
                target = key.first,
                route = key.second,
                attempts = latestPerPass.size,
                expectedPasses = expectedPasses,
                successes = successful.size,
                verifiedTls = verified,
                medianMs = middle,
                lastObservedAtMillis = latestPerPass.maxOf { it.endedAtMillis },
                completed = complete,
                stableProxyTls = expectedPasses >= 2 && complete &&
                    verified == expectedPasses && key.second == TargetProbeRoute.LOCAL_HTTP_CONNECT,
            )
        }.sortedWith(
            compareByDescending<CandidateStabilitySummary> { it.stableProxyTls }
                .thenByDescending { it.successes }
                .thenBy { it.medianMs ?: Long.MAX_VALUE }
                .thenBy { it.target.host }
                .thenBy { it.target.port }
        )
    }
}
