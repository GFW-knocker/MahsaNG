package com.v2ray.ang.util

/** Only for TCP socket-connect probes; never applied to native proxy/UDP checks. */
internal enum class TcpProbePreset(val attempts: Int, val timeoutMs: Int) {
    QUICK(1, 1_500),
    BALANCED(2, 3_000),
    DIAGNOSTIC(3, 5_000);

    companion object {
        /** Unknown, corrupt or legacy preference values retain the prior conservative budget. */
        fun fromStored(value: String?): TcpProbePreset = when (value) {
            "quick" -> QUICK
            "diagnostic" -> DIAGNOSTIC
            else -> BALANCED
        }
    }

    /** Stop after the first verified TCP connect; never turn cancellation into success. */
    fun probe(cancelled: () -> Boolean, connect: (timeoutMs: Int) -> Long): Long {
        repeat(attempts) {
            if (cancelled()) return -1L
            val delay = connect(timeoutMs)
            if (cancelled()) return -1L
            if (delay > 0L) return delay
        }
        return -1L
    }
}
