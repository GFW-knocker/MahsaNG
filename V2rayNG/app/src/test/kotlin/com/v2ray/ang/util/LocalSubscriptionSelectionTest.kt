package com.v2ray.ang.util

import com.v2ray.ang.dto.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubscriptionSelectionTest {
    @Test fun reusesOnlyMatchingLocalGroupNotRemoteFeed() {
        val remote = "remote" to SubscriptionItem(
            remarks = "GFW-knocker", url = "https://example.org/feed"
        )
        val local = "local" to SubscriptionItem(
            remarks = "GFW-knocker", url = "", enabled = false
        )
        assertEquals(
            "local", LocalSubscriptionSelection.existingLocalId(
                "GFW-knocker", listOf(remote, local)
            )
        )
    }

    @Test fun remoteSubscriptionWithSameNameIsNeverReused() {
        val remote = "remote" to SubscriptionItem(
            remarks = "GFW-knocker", url = "https://example.org/feed"
        )
        assertNull(LocalSubscriptionSelection.existingLocalId("GFW-knocker", listOf(remote)))
    }

    @Test fun blankNamesAndIdsAreRejected() {
        val local = "local" to SubscriptionItem(remarks = "GFW-knocker", url = "")
        assertNull(LocalSubscriptionSelection.existingLocalId("  ", listOf(local)))
        assertNull(LocalSubscriptionSelection.existingLocalId(
            "GFW-knocker", listOf("" to SubscriptionItem(remarks = "GFW-knocker", url = ""))
        ))
    }

    @Test fun existingLocalGroupIsChosenDeterministically() {
        val first = "local-a" to SubscriptionItem(remarks = "GFW-knocker", url = "")
        val second = "local-b" to SubscriptionItem(remarks = "GFW-knocker", url = "")
        assertEquals("local-a", LocalSubscriptionSelection.existingLocalId(
            " GFW-knocker ", listOf(first, second)
        ))
    }
}
