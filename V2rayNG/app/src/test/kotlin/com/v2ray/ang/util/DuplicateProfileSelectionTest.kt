package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateProfileSelectionTest {
    @Test fun selectedDuplicateSurvivesEvenIfNotFirstInList() {
        val identities = mapOf("first" to "same", "selected" to "same", "third" to "same", "unique" to "other")
        val duplicates = DuplicateProfileSelection.findDuplicateGuids(
            listOf("first", "selected", "third", "unique"), identities.keys, "selected"
        ) { identities[it] }
        assertEquals(listOf("first", "third"), duplicates)
    }

    @Test fun onlyVisibleIndexedGuidsAreEverSelectedForRemoval() {
        val visible = listOf("shownA", "shownB", "missing", "shownA")
        val stored = listOf("hidden", "shownA", "shownB")
        val identities = mapOf("hidden" to "same", "shownA" to "same", "shownB" to "same", "missing" to "same")
        assertEquals(listOf("shownB"), DuplicateProfileSelection.findDuplicateGuids(visible, stored, null) { identities[it] })
    }

    @Test fun noMeasuredIdentityMeansNeverDeleteTheProfile() {
        val identities = mapOf("invalid" to null, "a" to "key", "b" to "key", "unknown" to null)
        assertEquals(listOf("b"), DuplicateProfileSelection.findDuplicateGuids(
            identities.keys.toList(), identities.keys.toList(), null
        ) { identities[it] })
    }

    @Test fun candidateIdentityIsReadOnlyOncePerDistinctVisibleGuid() {
        val calls = mutableMapOf<String, Int>()
        DuplicateProfileSelection.findDuplicateGuids(
            listOf("a", "b", "a", "b", "c"), listOf("a", "b", "c"), null
        ) { guid ->
            calls[guid] = 1 + (calls[guid] ?: 0)
            "same"
        }
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), calls)
    }

    @Test fun distinctConfigurationsAndSingleVisibleItemsArePreserved() {
        assertEquals(emptyList<String>(), DuplicateProfileSelection.findDuplicateGuids(
            listOf("a", "b", "c"), listOf("a", "b", "c"), "not-visible"
        ) { it })
    }

    @Test fun removalsFollowVisibleOrderAcrossDifferentDuplicateGroups() {
        val identities = mapOf("firstA" to "A", "firstB" to "B", "secondB" to "B", "secondA" to "A")
        assertEquals(listOf("secondB", "secondA"), DuplicateProfileSelection.findDuplicateGuids(
            listOf("firstA", "firstB", "secondB", "secondA"), identities.keys, null
        ) { identities[it] })
    }

    @Test fun originalListsRemainUnchangedEvenWhenCallerMutatesThemLater() {
        val visible = mutableListOf("first", "second", "other")
        val stored = mutableListOf("first", "second", "other")
        val duplicates = DuplicateProfileSelection.findDuplicateGuids(visible, stored, null) { guid ->
            if (guid == "first") "match" else if (guid == "second") "match" else "other"
        }
        visible.clear()
        stored.clear()
        assertEquals(listOf("second"), duplicates)
    }
}
