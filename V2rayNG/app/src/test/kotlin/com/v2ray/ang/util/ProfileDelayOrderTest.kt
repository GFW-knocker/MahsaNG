package com.v2ray.ang.util

import org.junit.Test
import org.junit.Assert.assertEquals

class ProfileDelayOrderTest {
    @Test fun successfulResultsSortAscendingAndTiesStayInOriginalOrder() {
        val delays = mapOf("slow" to 90L, "fastA" to 12L, "fastB" to 12L)
        assertEquals(listOf("fastA", "fastB", "slow"), ProfileDelayOrder.order(
            listOf("slow", "fastA", "fastB"),
        ) { delays[it] })
    }

    @Test fun untestedAndFailedAreRetainedAndStayInOriginalOrder() {
        val delays = mapOf("not-tested" to 0L, "failed" to -1L, "working" to 42L)
        assertEquals(listOf("working", "not-tested", "failed", "missing"), ProfileDelayOrder.order(
            listOf("not-tested", "failed", "missing", "working"),
        ) { delays[it] })
    }

    @Test fun scoreSnapshotCannotBeMutatedDuringComparison() {
        val guids = mutableListOf("a", "b", "c")
        val sorted = ProfileDelayOrder.order(guids) { guid ->
            guids.clear()
            mapOf("a" to 3L, "b" to 1L, "c" to 2L)[guid]
        }
        assertEquals(listOf("b", "c", "a"), sorted)
    }

    @Test fun scoresAreFetchedOncePerDistinctProfile() {
        val calls = mutableMapOf<String, Int>()
        ProfileDelayOrder.order(listOf("a", "b", "a")) { guid ->
            calls[guid] = 1 + (calls[guid] ?: 0)
            50L
        }
        assertEquals(mapOf("a" to 1, "b" to 1), calls)
    }

    @Test fun emptyInputDoesNotConsultStorage() {
        val result = ProfileDelayOrder.order(emptyList()) { error("unexpected read") }
        assertEquals(emptyList<String>(), result)
    }
}
