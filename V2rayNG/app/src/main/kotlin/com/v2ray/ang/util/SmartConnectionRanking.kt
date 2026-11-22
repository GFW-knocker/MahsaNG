package com.v2ray.ang.util

import kotlin.math.min

/**
 * Native outbound HTTP-probe history. Not TCP reachability, VPN forwarding, throughput
 * or DNS-leak proof. Only the last eight distinct real-ping runs are counted.
 *
 * The profile revision is a digest, never a credential-bearing configuration.
 */
data class SmartRealPingEvidence(
    val profileRevision: String = "",
    val latestRunId: String = "",
    val observedAtMillis: Long = 0,
    val lastDelayMillis: Long = 0,
    val successBits: Int = 0,
    val samples: Int = 0,
)

data class SmartRecommendation(
    val guid: String,
    val profileRevision: String,
    val latestRunId: String,
    val observedAtMillis: Long,
    val delayMillis: Long,
    val successes: Int,
    val samples: Int,
    val score: Double,
)

/** Storage-independent, deterministic recommendation policy based on observed native results. */
object SmartConnectionRanking {
    const val MAX_AGE_MILLIS = 30 * 60_000L
    const val HISTORY_RESET_MILLIS = 24 * 60 * 60_000L
    private const val MAX_SAMPLES = 8
    private const val MAX_DELAY_MILLIS = 60_000L

    data class Entry(
        val guid: String,
        val currentProfileRevision: String,
        val evidence: SmartRealPingEvidence?,
    )

    /**
     * Return null for invalid identifiers/timestamps; a failed native probe is a
     * legitimate history sample, but never an eligible latest recommendation.
     * A repeated delivery for the same run must not inflate reliability.
     */
    fun record(
        previous: SmartRealPingEvidence?,
        profileRevision: String,
        runId: String,
        delayMillis: Long,
        measuredAtMillis: Long,
    ): SmartRealPingEvidence? {
        if (profileRevision.isBlank() || runId.isBlank() || measuredAtMillis <= 0) return null
        if (previous?.profileRevision == profileRevision && previous.latestRunId == runId) {
            return previous
        }
        val reuse = previous?.takeIf {
            it.profileRevision == profileRevision &&
                it.observedAtMillis in 1..measuredAtMillis &&
                measuredAtMillis - it.observedAtMillis <= HISTORY_RESET_MILLIS &&
                it.samples in 1..MAX_SAMPLES &&
                it.successBits in 0..((1 shl it.samples) - 1)
        }
        val good = delayMillis in 1..MAX_DELAY_MILLIS
        val count = min(MAX_SAMPLES, (reuse?.samples ?: 0) + 1)
        val goodBit = if (good) 1 else 0
        val bits = (((reuse?.successBits ?: 0) shl 1) or goodBit) and ((1 shl count) - 1)
        return SmartRealPingEvidence(
            profileRevision = profileRevision,
            latestRunId = runId,
            observedAtMillis = measuredAtMillis,
            lastDelayMillis = if (good) delayMillis else 0,
            successBits = bits,
            samples = count,
        )
    }

    fun successes(evidence: SmartRealPingEvidence): Int =
        Integer.bitCount(evidence.successBits and ((1 shl evidence.samples.coerceIn(0, MAX_SAMPLES)) - 1))

    private fun eligible(entry: Entry, nowMillis: Long): SmartRecommendation? {
        val value = entry.evidence ?: return null
        if (entry.guid.isBlank() || entry.currentProfileRevision.isBlank() ||
            entry.currentProfileRevision != value.profileRevision || value.latestRunId.isBlank() ||
            value.samples !in 1..MAX_SAMPLES ||
            value.successBits !in 0..((1 shl value.samples) - 1) ||
            value.successBits and 1 != 1 || value.lastDelayMillis !in 1..MAX_DELAY_MILLIS ||
            value.observedAtMillis <= 0 || nowMillis < value.observedAtMillis
        ) return null

        val age = nowMillis - value.observedAtMillis
        if (age > MAX_AGE_MILLIS) return null
        val successes = successes(value)
        // Simple Bayesian smoothing avoids treating one success as 100% certainty.
        val reliability = (successes + 1.0) / (value.samples + 2.0)
        val latency = 1.0 - min(2_000L, value.lastDelayMillis) / 2_000.0
        val freshness = 1.0 - age / MAX_AGE_MILLIS.toDouble()
        return SmartRecommendation(
            guid = entry.guid,
            profileRevision = value.profileRevision,
            latestRunId = value.latestRunId,
            observedAtMillis = value.observedAtMillis,
            delayMillis = value.lastDelayMillis,
            successes = successes,
            samples = value.samples,
            score = 0.55 * reliability + 0.35 * latency + 0.10 * freshness,
        )
    }

    /**
     * Rank only caller-scoped profiles. Hysteresis avoids suggesting a needless
     * change when the currently selected, eligible profile performs similarly.
     * This only returns a preview: it never starts, stops or switches a VPN.
     */
    fun pick(entries: Collection<Entry>, selectedGuid: String?, nowMillis: Long): SmartRecommendation? {
        val ranked = entries.distinctBy { it.guid }.mapNotNull { eligible(it, nowMillis) }
            .sortedWith(compareByDescending<SmartRecommendation> { it.score }.thenBy { it.delayMillis })
        val best = ranked.firstOrNull() ?: return null
        val selected = ranked.firstOrNull { it.guid == selectedGuid } ?: return best
        if (best.guid == selected.guid) return selected
        val gain = best.score - selected.score
        return if (gain <= maxOf(0.05, selected.score * 0.15)) selected else best
    }

    /** Reject a dialog preview if its measurement changed, aged out, or profile was edited. */
    fun matchesPreview(
        preview: SmartRecommendation,
        currentProfileRevision: String,
        evidence: SmartRealPingEvidence?,
        nowMillis: Long,
    ): Boolean {
        val latest = eligible(Entry(preview.guid, currentProfileRevision, evidence), nowMillis)
            ?: return false
        return latest.profileRevision == preview.profileRevision &&
            latest.latestRunId == preview.latestRunId &&
            latest.observedAtMillis == preview.observedAtMillis &&
            latest.delayMillis == preview.delayMillis &&
            latest.samples == preview.samples &&
            latest.successes == preview.successes
    }
}
