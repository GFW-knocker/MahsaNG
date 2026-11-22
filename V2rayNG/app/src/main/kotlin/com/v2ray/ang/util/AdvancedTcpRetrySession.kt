package com.v2ray.ang.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** A read-only diagnostic report; none of these counts authorizes deletion. */
internal data class AdvancedTcpRetryReport(
    val runId: String,
    val snapshot: AdvancedRetrySnapshot,
    val totalPasses: Int,
    val previewLastThreePasses: Int = 0,
    val previewThreeOfFive: Int = 0,
    val previewLastThreeAttempts: Int = 0,
    val sequence: Long = 0L,
)

/**
 * Owns ONLY the direct TCP sockets opened by this run. It never touches the
 * global core, stored delays or subscriptions. Stop invalidates tickets before
 * best-effort socket close; a blocked DNS lookup cannot publish a late result.
 */
internal class AdvancedTcpRetrySession(
    private val scope: CoroutineScope,
    val runId: String,
    targets: List<AdvancedRetryTarget>,
    settings: AdvancedRetrySettings,
    mode: RetryPassMode,
    private val timeoutMs: Int,
    private val currentRevision: (String) -> String?,
    private val networkStillCurrent: () -> Boolean,
    private val onReport: (AdvancedTcpRetryReport) -> Unit,
    // Injectable deterministic adapter for CI; production owns each Socket.
    private val testConnect: ((AdvancedRetryTarget, Int) -> Long)? = null,
) {
    private val targets = targets.toList()
    private val scheduler = AdvancedRetryScheduler(runId, this.targets, settings, mode)
    private val ledger = AdvancedRetryReview(
        runId, this.targets.associate { it.guid to it.revision }, settings,
        RetryProbeMethod.TCP_CONNECT,
    )
    private val socketLock = Any()
    private val ownedSockets = mutableSetOf<Socket>()
    private var stopped = false
    private val lastReportNs = AtomicLong(0L)
    private val reportSequence = AtomicLong(0L)
    private var job: Job? = null

    init { require(timeoutMs in 500..10_000) }

    fun start() {
        check(job == null) { "A retry session can only be started once" }
        if (scheduler.workerCount == 0) scheduler.next()
        onReport(report())
        job = scope.launch(Dispatchers.IO) {
            coroutineScope {
                repeat(scheduler.workerCount) {
                    launch(Dispatchers.IO) worker@ {
                        while (isActive) {
                            when (val poll = scheduler.next()) {
                                is AdvancedRetryPoll.Work -> {
                                    val outcome = measure(poll.task)
                                    if (isActive && scheduler.complete(poll.task, outcome) {
                                            check(ledger.record(
                                                runId, poll.task.target.guid,
                                                poll.task.target.revision, poll.task.pass,
                                                poll.task.attempt,
                                                when (outcome) {
                                                    AdvancedRetryAttemptOutcome.SUCCESS -> RetryObservation.SUCCESS
                                                    AdvancedRetryAttemptOutcome.FAIL -> RetryObservation.FAIL
                                                    AdvancedRetryAttemptOutcome.UNKNOWN -> RetryObservation.UNKNOWN
                                                },
                                            )) { "Rejected in-order retry evidence" }
                                        }) {
                                        publishProgressThrottled()
                                    }
                                }
                                AdvancedRetryPoll.Idle -> delay(30L)
                                AdvancedRetryPoll.Paused -> delay(90L)
                                AdvancedRetryPoll.Finished -> return@worker
                            }
                        }
                    }
                }
            }
            if (isActive && scheduler.snapshot().phase == RetryRunPhase.FINISHED) {
                ledger.finish()
                val revisions = targets.associate { it.guid to currentRevision(it.guid).orEmpty() }
                onReport(report(
                    lastPasses = ledger.reviewMatches(RetryReviewRule.LAST_THREE_FAILED_PASSES, revisions).size,
                    threeOfFive = ledger.reviewMatches(RetryReviewRule.THREE_OF_FIVE_FAILED_PASSES, revisions).size,
                    lastAttempts = ledger.reviewMatches(RetryReviewRule.LAST_THREE_FAILED_ATTEMPTS, revisions).size,
                ))
            }
        }
    }

    private fun measure(task: AdvancedRetryTask): AdvancedRetryAttemptOutcome {
        val target = task.target
        if (!networkStillCurrent() || currentRevision(target.guid) != target.revision) {
            return AdvancedRetryAttemptOutcome.UNKNOWN
        }
        return try {
            val elapsed = testConnect?.invoke(target, timeoutMs)
                ?: SocketConnectTimer.measure(
                    target.host, target.port, timeoutMs, Socket(),
                    onRegister = { socket ->
                        synchronized(socketLock) {
                            check(!stopped) { "Retry run stopped" }
                            ownedSockets.add(socket)
                        }
                    },
                    onUnregister = { socket -> synchronized(socketLock) { ownedSockets.remove(socket) } },
                )
            if (!networkStillCurrent() || currentRevision(target.guid) != target.revision) {
                AdvancedRetryAttemptOutcome.UNKNOWN
            } else if (elapsed > 0L) {
                AdvancedRetryAttemptOutcome.SUCCESS
            } else {
                AdvancedRetryAttemptOutcome.FAIL
            }
        } catch (_: Exception) {
            if (!networkStillCurrent() || currentRevision(target.guid) != target.revision) {
                AdvancedRetryAttemptOutcome.UNKNOWN
            } else {
                AdvancedRetryAttemptOutcome.FAIL
            }
        }
    }

    private fun report(lastPasses: Int = 0, threeOfFive: Int = 0, lastAttempts: Int = 0) =
        AdvancedTcpRetryReport(runId, scheduler.snapshot(), scheduler.settings.passes,
            lastPasses, threeOfFive, lastAttempts, reportSequence.incrementAndGet())

    private fun publishProgressThrottled() {
        val now = System.nanoTime()
        val previous = lastReportNs.get()
        if (now - previous > 250_000_000L && lastReportNs.compareAndSet(previous, now)) {
            onReport(report())
        }
    }

    fun pause(): Boolean {
        if (!scheduler.pause()) return false
        ledger.pause()
        onReport(report())
        return true
    }

    fun resume(): Boolean {
        if (!scheduler.resume()) return false
        ledger.resume()
        onReport(report())
        return true
    }

    fun stop(): Boolean {
        // Claim the socket-registration lock before stopping the scheduler.
        // A new socket can neither escape the snapshot nor start after Stop.
        val sockets = synchronized(socketLock) {
            stopped = true
            ownedSockets.toList()
        }
        if (!scheduler.stop()) return false
        ledger.stop()
        job?.cancel()
        sockets.forEach { runCatching { it.close() } }
        onReport(report())
        return true
    }

    fun snapshot() = report()
}
