package com.v2ray.ang.util

/** Stored profile delays: positive is measured, negative failed, zero/absent untested. */
internal enum class ProfileProbeStatus { ALL, SUCCESS, FAILURE, UNTESTED }

/** Read-only result filtering; independent of group and keyword selection. */
internal object ProfileProbeFilter {
    fun matches(delayMs: Long?, status: ProfileProbeStatus, maxLatencyMs: Long?): Boolean {
        val delay = delayMs ?: 0L
        val byStatus = when (status) {
            ProfileProbeStatus.ALL -> true
            ProfileProbeStatus.SUCCESS -> delay > 0
            ProfileProbeStatus.FAILURE -> delay < 0
            ProfileProbeStatus.UNTESTED -> delay == 0L
        }
        // A latency cap is meaningful only for positive measured delays.
        // Invalid caps fail closed even if callers forget to validate the input.
        val byLatency = maxLatencyMs == null ||
            (maxLatencyMs in 1..60_000 && delay > 0 && delay <= maxLatencyMs)
        return byStatus && byLatency
    }
}
