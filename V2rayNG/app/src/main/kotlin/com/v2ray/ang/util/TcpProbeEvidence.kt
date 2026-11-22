package com.v2ray.ang.util

import java.util.concurrent.TimeUnit

/** Ephemeral TCP socket observations; never persisted or mistaken for proxy success. */
internal data class TcpProbeAttempt(val elapsedMs: Long, val succeeded: Boolean,
    val failure: SafeProbeFailure.Kind?)
internal data class TcpProbeEvidence(val delayMs: Long, val totalElapsedMs: Long,
    val attempts: List<TcpProbeAttempt>)

/** Reuses the existing bounded preset without adding sockets, workers or retries. */
internal object TcpProbeEvidenceRunner {
    fun measure(preset: TcpProbePreset, cancelled: () -> Boolean,
        connect: (Int) -> Pair<Long, SafeProbeFailure.Kind?>): TcpProbeEvidence {
        val started = System.nanoTime()
        val observed = mutableListOf<TcpProbeAttempt>()
        val delay = preset.probe(cancelled) { timeoutMs ->
            val attemptStarted = System.nanoTime()
            val (value, kind) = connect(timeoutMs)
            observed.add(TcpProbeAttempt(elapsed(attemptStarted), value > 0,
                if (value > 0) null else kind))
            value
        }
        return TcpProbeEvidence(delay, elapsed(started), observed.toList())
    }

    private fun elapsed(start: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
}
