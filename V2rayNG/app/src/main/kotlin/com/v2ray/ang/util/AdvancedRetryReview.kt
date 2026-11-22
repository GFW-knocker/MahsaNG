package com.v2ray.ang.util

/** Evidence only. Nothing in this model deletes a profile or starts network work. */
internal enum class RetryObservation { SUCCESS, FAIL, UNKNOWN }
internal enum class RetryPassOutcome { PASS, FAIL, UNKNOWN }
internal enum class RetryReviewRule {
    LAST_THREE_FAILED_PASSES,
    THREE_OF_FIVE_FAILED_PASSES,
    LAST_THREE_FAILED_ATTEMPTS,
}
internal enum class RetryRunPhase { RUNNING, PAUSED, STOPPED, FINISHED }
internal enum class RetryProbeMethod { TCP_CONNECT, PROXY_HTTP }

/** Conservative, finite limits for the optional advanced diagnostic preview. */
internal data class AdvancedRetrySettings(
    val attemptsPerPass: Int,
    val passes: Int,
    val concurrency: Int,
) {
    init {
        require(attemptsPerPass in 1..5) { "Attempt count must be 1..5" }
        require(passes in 1..10) { "Pass count must be 1..10" }
        require(concurrency in 1..16) { "Concurrency must be 1..16" }
    }

    fun maximumAttempts(profileCount: Int): Long {
        require(profileCount >= 0)
        return profileCount.toLong() * attemptsPerPass * passes
    }

    /** Requires explicit future UI approval; never silently start a large run. */
    fun needsBudgetApproval(profileCount: Int): Boolean = maximumAttempts(profileCount) > 20_000L
}

/**
 * In-memory, run- and revision-owned evidence for a future optional retry UI.
 * This is not an executor and deliberately exposes no deletion API.
 *
 * Work may complete after Pause, but late completions following Stop are
 * discarded. An external caller must also verify current revisions before
 * using any preview, because saved profiles can change while a run executes.
 */
internal class AdvancedRetryReview(
    val runId: String,
    profileRevisions: Map<String, String>,
    val settings: AdvancedRetrySettings,
    val method: RetryProbeMethod = RetryProbeMethod.TCP_CONNECT,
) {
    private val revisions = profileRevisions.toMap()
    private val observations = mutableMapOf<String, MutableMap<Int, MutableMap<Int, RetryObservation>>>()
    var phase: RetryRunPhase = RetryRunPhase.RUNNING
        private set

    init {
        require(runId.isNotBlank())
        require(revisions.keys.none { it.isBlank() })
        require(revisions.values.none { it.isBlank() })
    }

    @Synchronized
    fun maySchedule(): Boolean = phase == RetryRunPhase.RUNNING

    @Synchronized
    fun pause(): Boolean {
        if (phase != RetryRunPhase.RUNNING) return false
        phase = RetryRunPhase.PAUSED
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        if (phase != RetryRunPhase.PAUSED) return false
        phase = RetryRunPhase.RUNNING
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (phase == RetryRunPhase.STOPPED || phase == RetryRunPhase.FINISHED) return false
        phase = RetryRunPhase.STOPPED
        observations.clear() // aborted evidence cannot be used as a failure verdict
        return true
    }

    @Synchronized
    fun finish(): Boolean {
        if (phase != RetryRunPhase.RUNNING && phase != RetryRunPhase.PAUSED) return false
        phase = RetryRunPhase.FINISHED
        return true
    }

    /** Atomic ownership check before accepting one completed attempt. */
    @Synchronized
    fun record(
        eventRunId: String,
        guid: String,
        testedRevision: String,
        pass: Int,
        attempt: Int,
        result: RetryObservation,
        observedMethod: RetryProbeMethod = method,
    ): Boolean {
        if (phase != RetryRunPhase.RUNNING && phase != RetryRunPhase.PAUSED) return false
        if (eventRunId != runId || revisions[guid] != testedRevision || observedMethod != method) return false
        if (pass !in 1..settings.passes || attempt !in 1..settings.attemptsPerPass) return false
        val previous = observations[guid]?.get(pass)
        if (previous?.containsKey(attempt) == true) return false
        // Per-profile retries must be sequential and stop after success.
        if (attempt > 1 && previous?.containsKey(attempt - 1) != true) return false
        if (previous?.values?.contains(RetryObservation.SUCCESS) == true) return false
        val byPass = observations.getOrPut(guid) { mutableMapOf() }
        byPass.getOrPut(pass) { mutableMapOf() }[attempt] = result
        return true
    }

    @Synchronized
    fun passResult(guid: String, pass: Int): RetryPassOutcome {
        if (guid !in revisions || pass !in 1..settings.passes) return RetryPassOutcome.UNKNOWN
        val attempts = observations[guid]?.get(pass) ?: return RetryPassOutcome.UNKNOWN
        if (RetryObservation.SUCCESS in attempts.values) return RetryPassOutcome.PASS
        return if (attempts.size == settings.attemptsPerPass &&
            attempts.values.all { it == RetryObservation.FAIL }
        ) RetryPassOutcome.FAIL else RetryPassOutcome.UNKNOWN
    }

    /** Read-only candidates for manual review, NEVER automatic deletion. */
    @Synchronized
    fun reviewMatches(rule: RetryReviewRule, currentRevisions: Map<String, String>): Set<String> {
        if (phase != RetryRunPhase.FINISHED) return emptySet()
        return revisions.keys.filterTo(linkedSetOf()) { guid ->
            if (revisions[guid] != currentRevisions[guid]) return@filterTo false
            val passOutcomes = (1..settings.passes).map { passResult(guid, it) }
            when (rule) {
                RetryReviewRule.LAST_THREE_FAILED_PASSES ->
                    passOutcomes.size >= 3 && passOutcomes.takeLast(3).all { it == RetryPassOutcome.FAIL }
                RetryReviewRule.THREE_OF_FIVE_FAILED_PASSES ->
                    passOutcomes.size == 5 && passOutcomes.none { it == RetryPassOutcome.UNKNOWN } &&
                        passOutcomes.count { it == RetryPassOutcome.FAIL } >= 3
                RetryReviewRule.LAST_THREE_FAILED_ATTEMPTS -> {
                    // An unfinished final pass makes a cross-pass streak ambiguous.
                    if (passOutcomes.lastOrNull() == RetryPassOutcome.UNKNOWN) false
                    else {
                        val recent = (1..settings.passes).flatMap { pass ->
                            val attempts = observations[guid]?.get(pass)?.toSortedMap()?.values.orEmpty()
                            // Incomplete passes have unknown evidence that interrupts a failure streak.
                            if (attempts.size == settings.attemptsPerPass ||
                                RetryObservation.SUCCESS in attempts
                            ) attempts else listOf(RetryObservation.UNKNOWN)
                        }
                        recent.size >= 3 && recent.takeLast(3).all { it == RetryObservation.FAIL }
                    }
                }
            }
        }
    }
}
