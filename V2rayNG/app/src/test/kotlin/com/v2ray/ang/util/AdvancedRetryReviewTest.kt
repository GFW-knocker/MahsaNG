package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class AdvancedRetryReviewTest {
    @Test fun largeDiagnosticRequiresApprovalAndDoesNotOverflow() {
        val settings = AdvancedRetrySettings(attemptsPerPass = 2, passes = 5, concurrency = 8)
        assertEquals(100_000L, settings.maximumAttempts(10_000))
        assertTrue(settings.needsBudgetApproval(10_000))
        assertFalse(settings.needsBudgetApproval(3))
    }

    @Test fun settingsEnforceMethodSpecificFiniteLimits() {
        assertThrows(IllegalArgumentException::class.java) { AdvancedRetrySettings(0, 5, 8) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedRetrySettings(6, 5, 8) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedRetrySettings(1, 11, 8) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedRetrySettings(1, 2, 17) }
    }

    @Test fun runAndRevisionOwnershipGuardAndDuplicateResultRejection() {
        val ledger = AdvancedRetryReview("run", mapOf("a" to "rev-1"), AdvancedRetrySettings(2, 3, 1))
        assertFalse(ledger.record("stale", "a", "rev-1", 1, 1, RetryObservation.FAIL))
        assertFalse(ledger.record("run", "a", "edited", 1, 1, RetryObservation.FAIL))
        assertFalse(ledger.record("run", "unknown-guid", "rev-1", 1, 1, RetryObservation.FAIL))
        assertFalse(ledger.record("run", "a", "rev-1", 1, 2, RetryObservation.FAIL))
        assertTrue(ledger.record("run", "a", "rev-1", 1, 1, RetryObservation.FAIL))
        assertFalse(ledger.record("run", "a", "rev-1", 1, 1, RetryObservation.FAIL))
    }

    @Test fun methodMismatchesAndBlankRevisionsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            AdvancedRetryReview("bad", mapOf("a" to ""), AdvancedRetrySettings(1, 1, 1))
        }
        val ledger = AdvancedRetryReview(
            "run", mapOf("a" to "rev"), AdvancedRetrySettings(1, 1, 1),
            method = RetryProbeMethod.PROXY_HTTP,
        )
        assertFalse(ledger.record("run", "a", "rev", 1, 1, RetryObservation.FAIL,
            observedMethod = RetryProbeMethod.TCP_CONNECT))
        assertTrue(ledger.record("run", "a", "rev", 1, 1, RetryObservation.SUCCESS,
            observedMethod = RetryProbeMethod.PROXY_HTTP))
    }

    @Test fun pauseBlocksSchedulingButAcceptsAnAlreadyStartedAttempt() {
        val ledger = AdvancedRetryReview("run", mapOf("a" to "rev"), AdvancedRetrySettings(2, 3, 1))
        assertTrue(ledger.pause())
        assertFalse(ledger.maySchedule())
        assertTrue(ledger.record("run", "a", "rev", 1, 1, RetryObservation.FAIL))
        assertTrue(ledger.resume())
        assertTrue(ledger.maySchedule())
        assertTrue(ledger.record("run", "a", "rev", 1, 2, RetryObservation.FAIL))
    }

    @Test fun stopMakesOutstandingAndFutureEvidenceUnusable() {
        val ledger = AdvancedRetryReview("run", mapOf("a" to "rev"), AdvancedRetrySettings(1, 3, 1))
        ledger.record("run", "a", "rev", 1, 1, RetryObservation.FAIL)
        assertTrue(ledger.stop())
        assertFalse(ledger.record("run", "a", "rev", 2, 1, RetryObservation.FAIL))
        assertFalse(ledger.finish())
        assertTrue(ledger.reviewMatches(RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev")).isEmpty())
    }

    @Test fun exactThreeOfFiveNeedsComparableCompletePasses() {
        val settings = AdvancedRetrySettings(2, 5, 8)
        val run = AdvancedRetryReview("run", mapOf("a" to "rev"), settings)
        for (pass in 1..5) {
            run.record("run", "a", "rev", pass, 1, RetryObservation.FAIL)
            run.record("run", "a", "rev", pass, 2,
                if (pass == 3) RetryObservation.UNKNOWN else RetryObservation.FAIL)
        }
        assertTrue(run.finish())
        assertEquals(RetryPassOutcome.UNKNOWN, run.passResult("a", 3))
        assertFalse("unknown pass must never count toward 3-of-5",
            "a" in run.reviewMatches(RetryReviewRule.THREE_OF_FIVE_FAILED_PASSES, mapOf("a" to "rev")))
        assertTrue(run.reviewMatches(RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev")).isEmpty())
    }

    @Test fun onlyLastThreeCompletedFailuresQualifyAndProfileEditsInvalidatePreview() {
        val settings = AdvancedRetrySettings(2, 5, 8)
        val run = AdvancedRetryReview("run", mapOf("a" to "rev-1"), settings)
        for (pass in 1..5) {
            run.record("run", "a", "rev-1", pass, 1, RetryObservation.FAIL)
            run.record("run", "a", "rev-1", pass, 2, RetryObservation.FAIL)
        }
        run.finish()
        assertEquals(setOf("a"), run.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev-1")))
        assertEquals(setOf("a"), run.reviewMatches(
            RetryReviewRule.THREE_OF_FIVE_FAILED_PASSES, mapOf("a" to "rev-1")))
        assertTrue(run.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev-edited")).isEmpty())
    }

    @Test fun lastThreeAttemptsAcrossPassesAreBrokenByLaterSuccess() {
        val run = AdvancedRetryReview("run", mapOf("a" to "rev"), AdvancedRetrySettings(2, 3, 1))
        for (pass in 1..3) {
            run.record("run", "a", "rev", pass, 1, RetryObservation.FAIL)
            run.record("run", "a", "rev", pass, 2,
                if (pass == 3) RetryObservation.SUCCESS else RetryObservation.FAIL)
        }
        run.finish()
        assertTrue(run.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_ATTEMPTS, mapOf("a" to "rev")).isEmpty())
    }


    @Test fun skippedOrPartialPassBreaksCrossPassFailureStreak() {
        val settings = AdvancedRetrySettings(2, 3, 1)
        for (middle in listOf<RetryObservation?>(null, RetryObservation.FAIL, RetryObservation.UNKNOWN)) {
            val run = AdvancedRetryReview("run", mapOf("a" to "rev"), settings)
            for (pass in listOf(1, 3)) {
                for (attempt in 1..2) {
                    assertTrue(run.record("run", "a", "rev", pass, attempt, RetryObservation.FAIL))
                }
            }
            if (middle != null) {
                assertTrue(run.record("run", "a", "rev", 2, 1, middle))
            }
            assertEquals(RetryPassOutcome.UNKNOWN, run.passResult("a", 2))
            assertTrue(run.finish())
            assertTrue("Incomplete middle pass must break the streak: $middle",
                run.reviewMatches(RetryReviewRule.LAST_THREE_FAILED_ATTEMPTS,
                    mapOf("a" to "rev")).isEmpty())
        }

        val completed = AdvancedRetryReview("run", mapOf("a" to "rev"), settings)
        for (pass in 1..3) for (attempt in 1..2) {
            assertTrue(completed.record("run", "a", "rev", pass, attempt, RetryObservation.FAIL))
        }
        assertTrue(completed.finish())
        assertEquals(setOf("a"), completed.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_ATTEMPTS, mapOf("a" to "rev")))
    }

    @Test fun reviewOnlyAvailableAfterFinishAndNeverTriggersRemoval() {
        val run = AdvancedRetryReview("run", mapOf("a" to "rev"), AdvancedRetrySettings(1, 3, 1))
        for (pass in 1..3) run.record("run", "a", "rev", pass, 1, RetryObservation.FAIL)
        assertTrue(run.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev")).isEmpty())
        assertTrue(run.finish())
        assertEquals(setOf("a"), run.reviewMatches(
            RetryReviewRule.LAST_THREE_FAILED_PASSES, mapOf("a" to "rev")))
    }
}
