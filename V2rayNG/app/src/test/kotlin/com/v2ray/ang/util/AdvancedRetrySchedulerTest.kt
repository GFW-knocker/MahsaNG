package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class AdvancedRetrySchedulerTest {
    private fun target(id: String) = AdvancedRetryTarget(id, "rev-$id", "example.invalid", 443)
    private fun work(s: AdvancedRetryScheduler) = (s.next() as AdvancedRetryPoll.Work).task

    @Test fun retryCannotBeClaimedUntilPredecessorEvidenceIsAccepted() {
        val scheduler = AdvancedRetryScheduler("atomic", listOf(target("a")),
            AdvancedRetrySettings(2, 1, 2), RetryPassMode.ALL_EVERY_PASS)
        val first = work(scheduler)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val recorder = kotlin.concurrent.thread(isDaemon = true) {
            finished.set(scheduler.complete(first, AdvancedRetryAttemptOutcome.FAIL) {
                entered.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            })
        }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val nextAttempt = java.util.concurrent.atomic.AtomicReference<AdvancedRetryPoll?>()
            val requester = kotlin.concurrent.thread(isDaemon = true) {
                nextAttempt.set(scheduler.next())
            }
            try {
                Thread.sleep(30)
                assertNull("Retry cannot appear before evidence is recorded", nextAttempt.get())
            } finally { release.countDown() }
            recorder.join(2000)
            requester.join(2000)
            assertTrue(finished.get())
            assertEquals(2, (nextAttempt.get() as AdvancedRetryPoll.Work).task.attempt)
        } finally {
            release.countDown()
            recorder.join(2000)
        }
    }

    @Test fun waitsForEveryInflightClaimBeforeStartingNextPass() {
        val run = AdvancedRetryScheduler("batch", listOf(target("a"), target("b")),
            AdvancedRetrySettings(2, 3, 2), RetryPassMode.ALL_EVERY_PASS)
        val first = work(run)
        val second = work(run)
        assertNotEquals(first.target.guid, second.target.guid)
        assertEquals(AdvancedRetryPoll.Idle, run.next())
        assertTrue(run.complete(first, AdvancedRetryAttemptOutcome.FAIL))
        val retryA = work(run)
        assertEquals(first.target.guid, retryA.target.guid)
        assertEquals(2, retryA.attempt)
        assertTrue(run.complete(retryA, AdvancedRetryAttemptOutcome.SUCCESS))
        assertEquals(AdvancedRetryPoll.Idle, run.next())
        assertTrue(run.complete(second, AdvancedRetryAttemptOutcome.FAIL))
        assertTrue(run.complete(work(run), AdvancedRetryAttemptOutcome.FAIL))
        assertEquals(2, work(run).pass)
    }

    @Test fun unresolvedOnlySkipsSuccessButAllEveryPassRetestsIt() {
        val targets = listOf(target("a"), target("b"))
        val unresolved = AdvancedRetryScheduler("u", targets,
            AdvancedRetrySettings(1, 3, 2), RetryPassMode.UNRESOLVED_ONLY)
        val a = work(unresolved)
        val b = work(unresolved)
        assertTrue(unresolved.complete(a, AdvancedRetryAttemptOutcome.SUCCESS))
        assertTrue(unresolved.complete(b, AdvancedRetryAttemptOutcome.FAIL))
        val next = work(unresolved)
        assertEquals(b.target.guid, next.target.guid)
        assertEquals(2, next.pass)
        assertTrue(unresolved.complete(next, AdvancedRetryAttemptOutcome.SUCCESS))
        assertEquals(AdvancedRetryPoll.Finished, unresolved.next())
        assertEquals(RetryRunPhase.FINISHED, unresolved.snapshot().phase)

        val all = AdvancedRetryScheduler("all", listOf(target("a")),
            AdvancedRetrySettings(1, 2, 1), RetryPassMode.ALL_EVERY_PASS)
        assertTrue(all.complete(work(all), AdvancedRetryAttemptOutcome.SUCCESS))
        assertEquals(2, work(all).pass)
    }

    @Test fun pauseAcceptsInFlightCompletionsStopInvalidatesTickets() {
        val scheduler = AdvancedRetryScheduler("current", listOf(target("a")),
            AdvancedRetrySettings(2, 2, 1), RetryPassMode.ALL_EVERY_PASS)
        val first = work(scheduler)
        assertTrue(scheduler.pause())
        assertEquals(AdvancedRetryPoll.Paused, scheduler.next())
        assertTrue(scheduler.complete(first, AdvancedRetryAttemptOutcome.FAIL))
        assertEquals(AdvancedRetryPoll.Paused, scheduler.next())
        assertTrue(scheduler.resume())
        val pending = work(scheduler)
        assertEquals(2, pending.attempt)
        assertTrue(scheduler.stop())
        assertFalse(scheduler.complete(pending, AdvancedRetryAttemptOutcome.FAIL))
        assertFalse(scheduler.resume())
        assertFalse(scheduler.stop())
        assertEquals(AdvancedRetryPoll.Finished, scheduler.next())
    }

    @Test fun replayedForeignAndForgedClaimsDoNotMutateProgress() {
        val scheduler = AdvancedRetryScheduler("owned", listOf(target("a")),
            AdvancedRetrySettings(1, 2, 1), RetryPassMode.ALL_EVERY_PASS)
        val first = work(scheduler)
        assertFalse(scheduler.complete(first.copy(runId = "obsolete"), AdvancedRetryAttemptOutcome.FAIL))
        assertFalse(scheduler.complete(first.copy(ticket = first.ticket + 100), AdvancedRetryAttemptOutcome.FAIL))
        assertFalse(scheduler.complete(first.copy(target = target("other")), AdvancedRetryAttemptOutcome.FAIL))
        assertEquals(0L, scheduler.snapshot().attemptsCompleted)
        assertTrue(scheduler.complete(first, AdvancedRetryAttemptOutcome.SUCCESS))
        assertFalse(scheduler.complete(first, AdvancedRetryAttemptOutcome.FAIL))
        assertEquals(1L, scheduler.snapshot().attemptsCompleted)
    }

    @Test fun unknownResultsNeverCountAsEligibleFailure() {
        val scheduler = AdvancedRetryScheduler("unknown", listOf(target("a")),
            AdvancedRetrySettings(2, 1, 1), RetryPassMode.ALL_EVERY_PASS)
        assertTrue(scheduler.complete(work(scheduler), AdvancedRetryAttemptOutcome.UNKNOWN))
        assertTrue(scheduler.complete(work(scheduler), AdvancedRetryAttemptOutcome.FAIL))
        assertEquals(1L, scheduler.snapshot().failedAttempts)
        assertEquals(1L, scheduler.snapshot().unknownAttempts)
        assertEquals(AdvancedRetryPoll.Finished, scheduler.next())
    }

    @Test fun tenThousandTargetsKeepOnlyConcurrencyClaimsActive() {
        val scheduler = AdvancedRetryScheduler("large", (0 until 10_000).map { target("p$it") },
            AdvancedRetrySettings(3, 5, 8), RetryPassMode.ALL_EVERY_PASS)
        assertEquals(8, scheduler.workerCount)
        assertEquals(150_000L, scheduler.maximumAttempts)
        val claimed = (0 until 8).map { work(scheduler) }
        assertEquals(AdvancedRetryPoll.Idle, scheduler.next())
        assertEquals(8, scheduler.snapshot().inFlight)
        claimed.forEach { assertTrue(scheduler.complete(it, AdvancedRetryAttemptOutcome.SUCCESS)) }
        assertEquals(8L, scheduler.snapshot().attemptsCompleted)
    }

    @Test fun emptyRunAndInvalidTargetsFailClosed() {
        val empty = AdvancedRetryScheduler("empty", emptyList(),
            AdvancedRetrySettings(2, 3, 8), RetryPassMode.ALL_EVERY_PASS)
        assertEquals(0, empty.workerCount)
        assertEquals(AdvancedRetryPoll.Finished, empty.next())
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedRetryScheduler("bad", listOf(target("same"), target("same")),
                AdvancedRetrySettings(1, 1, 1), RetryPassMode.ALL_EVERY_PASS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedRetryScheduler("bad", listOf(target("a").copy(port = 0)),
                AdvancedRetrySettings(1, 1, 1), RetryPassMode.ALL_EVERY_PASS)
        }
    }
}
