package com.v2ray.ang.util

import org.junit.Test
import org.junit.Assert.assertEquals

class ProfileIndexSwapTest {
    @Test fun filteredVisibleSwapOnlyChangesMatchingGuids() {
        assertEquals(listOf("visibleB", "hidden1", "visibleA", "hidden2"),
            ProfileIndexSwap.swap(listOf("visibleA", "hidden1", "visibleB", "hidden2"), "visibleA", "visibleB"))
    }

    @Test fun unknownGuidDoesNotOverwriteNewerIndex() {
        assertEquals(null, ProfileIndexSwap.swap(listOf("existing", "newlyAdded"), "existing", "staleRemoved"))
    }

    @Test fun noOpSwapPreservesOrder() {
        assertEquals(listOf("a", "b"), ProfileIndexSwap.swap(listOf("a", "b"), "a", "a"))
    }

    @Test fun originalSnapshotRemainsUnchanged() {
        val original = mutableListOf("one", "two", "three")
        assertEquals(listOf("three", "two", "one"), ProfileIndexSwap.swap(original, "one", "three"))
        assertEquals(listOf("one", "two", "three"), original)
    }

    @Test fun blankGuidsCannotBeMoved() {
        assertEquals(null, ProfileIndexSwap.swap(listOf("one", "two"), "", "one"))
    }
}
