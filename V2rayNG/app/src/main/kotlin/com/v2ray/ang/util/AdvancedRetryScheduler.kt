package com.v2ray.ang.util

import java.util.ArrayDeque

/** The scheduler does not own sockets or modify saved profiles. */
internal data class AdvancedRetryTarget(
    val guid: String,
    val revision: String,
    val host: String,
    val port: Int,
)

internal enum class RetryPassMode { ALL_EVERY_PASS, UNRESOLVED_ONLY }
internal enum class AdvancedRetryAttemptOutcome { SUCCESS, FAIL, UNKNOWN }

/** The opaque ticket prevents late, duplicate and foreign completions. */
internal data class AdvancedRetryTask internal constructor(
    val runId: String,
    val ticket: Long,
    val target: AdvancedRetryTarget,
    val pass: Int,
    val attempt: Int,
)

internal sealed class AdvancedRetryPoll {
    data class Work(val task: AdvancedRetryTask) : AdvancedRetryPoll()
    object Idle : AdvancedRetryPoll()
    object Paused : AdvancedRetryPoll()
    object Finished : AdvancedRetryPoll()
}

internal data class AdvancedRetrySnapshot(
    val phase: RetryRunPhase,
    val pass: Int,
    val attemptsCompleted: Long,
    val inFlight: Int,
    val succeededProfiles: Int,
    val failedAttempts: Long,
    val unknownAttempts: Long,
    val totalTargets: Int,
)

/**
 * Deterministic multi-pass work cursor. Exactly C external workers repeatedly
 * call next()/complete(), never a Job per profile. A pass cannot advance until
 * all its attempts finish. No deletion API and no persistent profile writes.
 *
 * Pause stops new claims but in-flight results remain acceptable. Stop
 * invalidates all tickets synchronously, even if a socket is still blocked.
 */
internal class AdvancedRetryScheduler(
    val runId: String,
    targets: List<AdvancedRetryTarget>,
    val settings: AdvancedRetrySettings,
    val mode: RetryPassMode,
) {
    private val targets = targets.toList()
    private val inFlight = mutableMapOf<Long, AdvancedRetryTask>()
    private val pendingRetries = ArrayDeque<AdvancedRetryTask>()
    private val succeeded = mutableSetOf<String>()
    private var nextIndex = 0
    private var nextTicket = 1L
    private var currentPass = 1
    private var completed = 0L
    private var failed = 0L
    private var unknown = 0L
    private var state = RetryRunPhase.RUNNING

    init {
        require(runId.isNotBlank())
        require(this.targets.map { it.guid }.distinct().size == this.targets.size) { "GUIDs must be unique" }
        require(this.targets.all {
            it.guid.isNotBlank() && it.revision.isNotBlank() &&
                it.host.isNotBlank() && it.port in 1..65535
        })
    }

    val workerCount = minOf(this.targets.size, settings.concurrency)
    val maximumAttempts: Long = this.targets.size.toLong() * settings.attemptsPerPass * settings.passes

    @Synchronized
    fun next(): AdvancedRetryPoll {
        if (state == RetryRunPhase.STOPPED || state == RetryRunPhase.FINISHED) return AdvancedRetryPoll.Finished
        if (state == RetryRunPhase.PAUSED) return AdvancedRetryPoll.Paused
        if (inFlight.size >= settings.concurrency) return AdvancedRetryPoll.Idle
        while (true) {
            if (pendingRetries.isNotEmpty()) return claim(pendingRetries.removeFirst())
            if (nextIndex < targets.size) {
                val target = targets[nextIndex++]
                if (mode == RetryPassMode.UNRESOLVED_ONLY && target.guid in succeeded) continue
                return claim(AdvancedRetryTask(runId, 0, target, currentPass, 1))
            }
            if (inFlight.isNotEmpty()) return AdvancedRetryPoll.Idle
            if (currentPass >= settings.passes) {
                state = RetryRunPhase.FINISHED
                return AdvancedRetryPoll.Finished
            }
            currentPass++
            nextIndex = 0
        }
    }

    private fun claim(planned: AdvancedRetryTask): AdvancedRetryPoll.Work {
        val ticket = nextTicket++
        val work = planned.copy(ticket = ticket)
        inFlight[ticket] = work
        return AdvancedRetryPoll.Work(work)
    }

    /** False for unknown, duplicate, obsolete and stopped claims. */
    @Synchronized
    fun complete(task: AdvancedRetryTask, outcome: AdvancedRetryAttemptOutcome,
        beforeRelease: () -> Unit = {}): Boolean {
        if (state != RetryRunPhase.RUNNING && state != RetryRunPhase.PAUSED) return false
        if (task.runId != runId || inFlight[task.ticket] != task) return false
        // The retry is not claimable before the ledger has accepted its predecessor.
        beforeRelease()
        inFlight.remove(task.ticket)
        completed++
        when (outcome) {
            AdvancedRetryAttemptOutcome.SUCCESS -> succeeded.add(task.target.guid)
            AdvancedRetryAttemptOutcome.FAIL -> failed++
            AdvancedRetryAttemptOutcome.UNKNOWN -> unknown++
        }
        if (outcome != AdvancedRetryAttemptOutcome.SUCCESS && task.attempt < settings.attemptsPerPass) {
            pendingRetries.addLast(task.copy(ticket = 0, attempt = task.attempt + 1))
        }
        return true
    }

    @Synchronized
    fun pause(): Boolean {
        if (state != RetryRunPhase.RUNNING) return false
        state = RetryRunPhase.PAUSED
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        if (state != RetryRunPhase.PAUSED) return false
        state = RetryRunPhase.RUNNING
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (state == RetryRunPhase.FINISHED || state == RetryRunPhase.STOPPED) return false
        state = RetryRunPhase.STOPPED
        inFlight.clear()
        pendingRetries.clear()
        return true
    }

    @Synchronized
    fun snapshot() = AdvancedRetrySnapshot(
        phase = state,
        pass = minOf(currentPass, settings.passes),
        attemptsCompleted = completed,
        inFlight = inFlight.size,
        succeededProfiles = succeeded.size,
        failedAttempts = failed,
        unknownAttempts = unknown,
        totalTargets = targets.size,
    )
}
