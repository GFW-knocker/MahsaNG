package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConnectionRankingTest {
    private val now = 2_000_000_000_000L

    private fun recorded(
        previous: SmartRealPingEvidence? = null,
        revision: String = "revision-a",
        run: String = "run-1",
        delay: Long = 70,
        at: Long = now,
    ) = requireNotNull(SmartConnectionRanking.record(previous, revision, run, delay, at))

    private fun entry(guid: String, evidence: SmartRealPingEvidence?, revision: String = "revision-a") =
        SmartConnectionRanking.Entry(guid, revision, evidence)

    @Test fun legacyAndTcpOnlyResultsAreNeverRecommendations() {
        assertNull(SmartConnectionRanking.pick(listOf(entry("legacy", null)), null, now))
    }

    @Test fun freshRealPingResultCarriesProvenanceAndCanBeRecommended() {
        val history = recorded()
        val recommended = SmartConnectionRanking.pick(listOf(entry("a", history)), null, now)
        assertNotNull(recommended)
        assertEquals("a", recommended?.guid)
        assertEquals(70L, recommended?.delayMillis)
        assertEquals(1, recommended?.successes)
        assertEquals(1, recommended?.samples)
        assertEquals("run-1", recommended?.latestRunId)
        assertTrue(SmartConnectionRanking.matchesPreview(recommended!!, "revision-a", history, now))
    }

    @Test fun expiredFutureDatedAndChangedProfileEvidenceIsExcluded() {
        val history = recorded()
        val entries = listOf(entry("a", history))
        assertNull(SmartConnectionRanking.pick(entries, null, now + SmartConnectionRanking.MAX_AGE_MILLIS + 1))
        assertNull(SmartConnectionRanking.pick(entries, null, now - 1))
        assertNull(SmartConnectionRanking.pick(listOf(entry("a", history, "changed")), null, now))
    }

    @Test fun aFreshFailureMakesPreviousSuccessIneligible() {
        val first = recorded()
        val failed = recorded(first, run = "run-2", delay = -1L, at = now + 10)
        assertEquals(0L, failed.lastDelayMillis)
        assertEquals(2, failed.samples)
        assertNull(SmartConnectionRanking.pick(listOf(entry("a", failed)), null, now + 10))
    }

    @Test fun theLastEightNativeRunsDetermineReliability() {
        var history: SmartRealPingEvidence? = null
        repeat(10) { index ->
            history = recorded(history, run = "run-$index",
                delay = if (index < 2) -1 else 80, at = now + index)
        }
        assertEquals(8, history?.samples)
        assertEquals(8, SmartConnectionRanking.successes(history!!))
        assertEquals(255, history!!.successBits)
    }

    @Test fun duplicateResultFromOneRunDoesNotInventExtraReliability() {
        val first = recorded()
        val duplicate = recorded(first, run = "run-1", delay = 120, at = now + 1)
        assertEquals(first, duplicate)
        assertEquals(1, duplicate.samples)
    }

    @Test fun historyResetsAcrossProfileRevisionsAndLongGaps() {
        val first = recorded()
        val edited = recorded(first, revision = "revision-b", run = "run-2", delay = 90, at = now + 1)
        assertEquals(1, edited.samples)
        assertEquals("revision-b", edited.profileRevision)
        val stale = recorded(edited, run = "run-3", delay = 65,
            at = now + SmartConnectionRanking.HISTORY_RESET_MILLIS + 10)
        assertEquals(1, stale.samples)
    }

    @Test fun reliabilityBeatsAFlakyButSlightlyFasterRoute() {
        var stable: SmartRealPingEvidence? = null
        var flaky: SmartRealPingEvidence? = null
        repeat(8) { index ->
            stable = recorded(stable, run = "stable-$index", delay = 70, at = now + index)
            flaky = recorded(flaky, run = "flaky-$index",
                delay = if (index % 2 == 0) -1 else 40, at = now + index)
        }
        val recommendation = SmartConnectionRanking.pick(
            listOf(entry("flaky", flaky), entry("stable", stable)), null, now + 8)
        assertEquals("stable", recommendation?.guid)
    }

    @Test fun currentEligibleSelectionIsPreservedForTinyImprovements() {
        val current = recorded(run = "current", delay = 90)
        val challenger = recorded(run = "challenger", delay = 40)
        val recommended = SmartConnectionRanking.pick(
            listOf(entry("challenger", challenger), entry("selected", current)), "selected", now)
        assertEquals("selected", recommended?.guid)
    }

    @Test fun ineligibleSelectionDoesNotBlockFreshEligibleCandidate() {
        val old = recorded(run = "old", at = now - SmartConnectionRanking.MAX_AGE_MILLIS - 1)
        val fresh = recorded(run = "fresh")
        assertEquals("fresh", SmartConnectionRanking.pick(
            listOf(entry("old", old), entry("fresh", fresh)), "old", now)?.guid)
    }

    @Test fun previewCannotBeConfirmedAfterNewMeasurementOrProfileEdit() {
        val first = recorded()
        val preview = SmartConnectionRanking.pick(listOf(entry("a", first)), null, now)!!
        assertFalse(SmartConnectionRanking.matchesPreview(preview, "changed", first, now))
        val next = recorded(first, run = "run-2", delay = 60, at = now + 1)
        assertFalse(SmartConnectionRanking.matchesPreview(preview, "revision-a", next, now + 1))
        assertFalse(SmartConnectionRanking.matchesPreview(preview, "revision-a", first,
            now + SmartConnectionRanking.MAX_AGE_MILLIS + 1))
    }

    @Test fun malformedHistoryOrZeroDelayCannotBecomeRecommended() {
        val corrupt = SmartRealPingEvidence(profileRevision = "revision-a",
            latestRunId = "r", observedAtMillis = now, lastDelayMillis = 20,
            successBits = 0, samples = 9)
        assertNull(SmartConnectionRanking.pick(listOf(entry("corrupt", corrupt)), null, now))
        assertNull(SmartConnectionRanking.record(null, "", "run", 10, now))
        assertNull(SmartConnectionRanking.record(null, "revision-a", "", 10, now))
        assertNull(SmartConnectionRanking.record(null, "revision-a", "run", 10, 0))
    }
}
