package com.v2ray.ang.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdvancedTcpRetrySessionTest {
    private val target = AdvancedRetryTarget("guid", "rev", "example.invalid", 443)
    private val settings = AdvancedRetrySettings(2, 3, 1)

    @Test fun eachProgressReportGetsAnIncreasingUniqueSequence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<Long>())
        try {
            val session = AdvancedTcpRetrySession(scope, "ordered", listOf(target),
                AdvancedRetrySettings(1, 1, 1), RetryPassMode.ALL_EVERY_PASS,
                1000, { "rev" }, { true },
                onReport = { observed.add(it.sequence) },
                testConnect = { _, _ -> 20L })
            session.start()
            withTimeout(5000) {
                while (session.snapshot().snapshot.phase != RetryRunPhase.FINISHED) delay(20)
            }
            val sequence = synchronized(observed) { observed.toList() }
            assertTrue(sequence.isNotEmpty())
            assertEquals(sequence.size, sequence.distinct().size)
            assertTrue(sequence.all { it > 0 })
        } finally { scope.cancel() }
    }

    @Test fun concurrentAllFailedRetriesPreserveThreePassFailureStreakPreview() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference<AdvancedTcpRetryReport?>()
        val second = target.copy(guid = "other-guid", revision = "other-rev")
        val total = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val session = AdvancedTcpRetrySession(scope, "failure-streak",
                listOf(target, second), AdvancedRetrySettings(3, 3, 2),
                RetryPassMode.ALL_EVERY_PASS, 1000,
                currentRevision = { guid -> if (guid == "guid") "rev" else "other-rev" },
                networkStillCurrent = { true },
                onReport = { latest.set(it) },
                testConnect = { candidate, _ ->
                    // Two workers interleave separate profiles and retries.
                    if (candidate.guid == "guid") Thread.sleep(2)
                    total.incrementAndGet()
                    -1L
                },
            )
            session.start()
            withTimeout(7000) {
                while (latest.get()?.snapshot?.phase != RetryRunPhase.FINISHED) delay(15)
            }
            val finished = requireNotNull(latest.get())
            assertEquals(18, total.get()) // 2 profiles * 3 passes * 3 attempts
            assertEquals(18L, finished.snapshot.failedAttempts)
            assertEquals(2, finished.previewLastThreePasses)
            assertEquals(2, finished.previewLastThreeAttempts)
            assertEquals(0, finished.previewThreeOfFive)
        } finally {
            scope.cancel()
        }
    }

    @Test fun realWorkerLoopCompletesThreePassesWithOwnedTickets() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference<AdvancedTcpRetryReport?>()
        try {
            val session = AdvancedTcpRetrySession(scope, "session", listOf(target), settings,
                RetryPassMode.ALL_EVERY_PASS, 1_000,
                currentRevision = { "rev" }, networkStillCurrent = { true },
                onReport = { latest.set(it) },
                testConnect = { _, _ -> 1L },
            )
            session.start()
            withTimeout(5_000) {
                while (latest.get()?.snapshot?.phase != RetryRunPhase.FINISHED) delay(25)
            }
            val finished = requireNotNull(latest.get())
            assertEquals(3L, finished.snapshot.attemptsCompleted)
            assertEquals(0L, finished.snapshot.failedAttempts)
            assertEquals(1, finished.snapshot.succeededProfiles)
            assertFalse(session.stop())
        } finally {
            scope.cancel()
        }
    }

    @Test fun stopRejectsBlockedSocketCompletionEvenAfterItReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val session = AdvancedTcpRetrySession(scope, "late", listOf(target), settings,
                RetryPassMode.ALL_EVERY_PASS, 1_000,
                currentRevision = { "rev" }, networkStillCurrent = { true },
                onReport = {},
                testConnect = { _, _ ->
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    1L
                },
            )
            session.start()
            assertTrue("The fake TCP connect must block before Stop", entered.await(2, TimeUnit.SECONDS))
            assertTrue(session.stop())
            release.countDown()
            delay(100)
            assertEquals(RetryRunPhase.STOPPED, session.snapshot().snapshot.phase)
            assertEquals(0L, session.snapshot().snapshot.attemptsCompleted)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun editedProfileRevisionTurnsSuccessfulSocketConnectIntoUnknown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference<AdvancedTcpRetryReport?>()
        val revision = AtomicReference("rev")
        try {
            val session = AdvancedTcpRetrySession(scope, "edited", listOf(target),
                AdvancedRetrySettings(1, 1, 1), RetryPassMode.ALL_EVERY_PASS, 1_000,
                currentRevision = { revision.get() }, networkStillCurrent = { true },
                onReport = { latest.set(it) },
                testConnect = { _, _ -> revision.set("rev-updated"); 1L },
            )
            session.start()
            withTimeout(5_000) {
                while (latest.get()?.snapshot?.phase != RetryRunPhase.FINISHED) delay(25)
            }
            val summary = requireNotNull(latest.get())
            assertEquals(1L, summary.snapshot.unknownAttempts)
            assertEquals(0L, summary.snapshot.failedAttempts)
            assertEquals(0, summary.snapshot.succeededProfiles)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aHandoverIsUnknownNotEligibleTcpFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference<AdvancedTcpRetryReport?>()
        val current = AtomicBoolean(true)
        try {
            val session = AdvancedTcpRetrySession(scope, "handover", listOf(target),
                AdvancedRetrySettings(2, 1, 1), RetryPassMode.ALL_EVERY_PASS, 1_000,
                currentRevision = { "rev" }, networkStillCurrent = { current.get() },
                onReport = { latest.set(it) },
                testConnect = { _, _ ->
                    current.set(false)
                    1L
                },
            )
            session.start()
            withTimeout(5_000) {
                while (latest.get()?.snapshot?.phase != RetryRunPhase.FINISHED) delay(25)
            }
            val summary = requireNotNull(latest.get())
            assertEquals(2L, summary.snapshot.unknownAttempts)
            assertEquals(0L, summary.snapshot.failedAttempts)
            assertEquals(0, summary.snapshot.succeededProfiles)
        } finally {
            scope.cancel()
        }
    }
}
