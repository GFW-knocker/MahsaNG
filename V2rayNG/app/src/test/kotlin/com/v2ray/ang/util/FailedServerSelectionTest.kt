package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FailedServerSelectionTest {
    private fun observation(delay: Long, id: String = "observation-1") =
        FailureObservation(delay, id)

    @Test fun zeroAndMissingResultsAreNotFailures() {
        val delays = mapOf("untested" to 0L, "failed" to -1L, "healthy" to 45L)
        assertEquals(listOf("failed"), FailedServerSelection.findFailed(
            listOf("untested", "missing", "failed", "healthy"),
            listOf("untested", "missing", "failed", "healthy"),
        ) { delays[it] })
    }

    @Test fun selectionCannotRemoveHiddenOrOtherSubscriptionProfiles() {
        val delays = mapOf("A" to -1L, "B" to -1L, "hidden" to -1L)
        assertEquals(listOf("A"), FailedServerSelection.findFailed(
            listOf("A"), listOf("A", "B", "hidden"),
        ) { delays[it] })
    }

    @Test fun staleAndDuplicateVisibleIdsAreIgnored() {
        assertEquals(listOf("real"), FailedServerSelection.findFailed(
            listOf("removed", "real", "real"), listOf("real"),
        ) { -1L })
    }

    @Test fun emptyScopeNeverDeletesAnything() {
        assertEquals(emptyList<String>(), FailedServerSelection.findFailed(
            emptyList(), listOf("failed"),
        ) { -1L })
    }

    @Test fun changedProfileRevisionCannotBeDeletedAfterConfirmation() {
        val revisions = mutableMapOf("a" to "original-config", "b" to "original-other")
        val preview = FailedServerSelection.snapshotFailures(
            listOf("a"), listOf("a", "b"), { observation(-1L) }, { revisions[it] },
        )
        assertEquals(listOf("a"), preview.map { it.guid })
        revisions["a"] = "replaced-profile-with-same-guid"
        assertEquals(emptyList<String>(), FailedServerSelection.reconfirmFailures(
            preview, listOf("a", "b"), { observation(-1L) }, { revisions[it] },
        ))
    }

    @Test fun newMeasurementWithSameNumericalFailureCannotReuseOldConfirmation() {
        val observations = mutableMapOf("failed" to observation(-1L, "run-A"))
        val revisions = mapOf("failed" to "r1")
        val preview = FailedServerSelection.snapshotFailures(
            listOf("failed"), listOf("failed"), { observations[it] }, { revisions[it] },
        )
        assertEquals(listOf("failed"), preview.map { it.guid })

        observations["failed"] = observation(0L, "reset")
        observations["failed"] = observation(-1L, "run-B")
        assertEquals(emptyList<String>(), FailedServerSelection.reconfirmFailures(
            preview, listOf("failed"), { observations[it] }, { revisions[it] },
        ))
    }

    @Test fun clearedResultCannotBeDeletedAfterConfirmation() {
        val observations = mutableMapOf("failed" to observation(-1L, "run-A"))
        val preview = FailedServerSelection.snapshotFailures(
            listOf("failed"), listOf("failed"), { observations[it] }, { "r1" },
        )
        observations["failed"] = observation(0L, "reset")
        assertEquals(emptyList<String>(), FailedServerSelection.reconfirmFailures(
            preview, listOf("failed"), { observations[it] }, { "r1" },
        ))
    }

    @Test fun deletionNeverExpandsBeyondFrozenVisibleAndStoredScope() {
        val revisions = mapOf("a" to "r1", "hidden" to "r2")
        val preview = FailedServerSelection.snapshotFailures(
            listOf("a", "a"), listOf("a", "hidden"),
            { observation(-1L) }, { revisions[it] },
        )
        assertEquals(listOf("a"), preview.map { it.guid })
        assertEquals(listOf("a"), FailedServerSelection.reconfirmFailures(
            preview, listOf("a", "hidden"), { observation(-1L) }, { revisions[it] },
        ))
        assertEquals(emptyList<String>(), FailedServerSelection.reconfirmFailures(
            preview, listOf("hidden"), { observation(-1L) }, { revisions[it] },
        ))
    }

    @Test fun explicitNegativeFailuresRequireReadableRevisionAndObservationId() {
        val preview = FailedServerSelection.snapshotFailures(
            listOf("missing", "blank", "legacy", "valid"),
            listOf("missing", "blank", "legacy", "valid"),
            { guid -> if (guid == "legacy") FailureObservation(-1L, null) else observation(-1L) },
            { guid -> when (guid) { "missing" -> null; "blank" -> ""; else -> "r3" } },
        )
        assertEquals(listOf("valid"), preview.map { it.guid })
    }

    @Test fun intactObservationAndRevisionStillAllowConfirmedDeletion() {
        val preview = FailedServerSelection.snapshotFailures(
            listOf("a"), listOf("a"), { observation(-1L, "exact-result") }, { "same-config" },
        )
        assertEquals(listOf("a"), FailedServerSelection.reconfirmFailures(
            preview, listOf("a"), { observation(-1L, "exact-result") }, { "same-config" },
        ))
    }
}
