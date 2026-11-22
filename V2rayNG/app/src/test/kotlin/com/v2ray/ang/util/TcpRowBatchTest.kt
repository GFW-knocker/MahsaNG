package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpRowBatchTest {
    @Test fun repeatedObservationsProduceOneRowUpdate() {
        val batch = TcpRowBatch()
        batch.begin("run-a")
        assertTrue(batch.add("run-a", "g1"))
        assertTrue(batch.add("run-a", "g1"))
        assertEquals(listOf(1), batch.drain("run-a", listOf("g0", "g1", "g2")))
        assertTrue(batch.drain("run-a", listOf("g0", "g1", "g2")).isEmpty())
    }

    @Test fun filteredViewIsResolvedAtDrainTimeNotStartTime() {
        val batch = TcpRowBatch()
        batch.begin("run-a")
        batch.add("run-a", "g4")
        batch.add("run-a", "g2")
        assertEquals(listOf(0, 2), batch.drain("run-a", listOf("g4", "g3", "g2")))
    }

    @Test fun supersededRunsAndInvisibleGuidsCannotInvalidateNewRows() {
        val batch = TcpRowBatch()
        batch.begin("run-a")
        batch.add("run-a", "g2")
        batch.begin("run-b")
        assertFalse(batch.add("run-a", "g0"))
        assertTrue(batch.drain("run-a", listOf("g2")).isEmpty())
        batch.add("run-b", "hidden")
        assertTrue(batch.drain("run-b", listOf("g0")).isEmpty())
    }

    @Test fun contiguousRowsCollapseIntoFewRangeNotifications() {
        assertEquals(listOf(2..5, 9..9, 14..14), TcpRowBatch.ranges(listOf(5, 2, 3, 4, 9, 14, 3)))
        assertTrue(TcpRowBatch.ranges(emptyList()).isEmpty())
        assertEquals(listOf(0..0), TcpRowBatch.ranges(listOf(-1, 0, -5)))
    }

    @Test fun replayedBatchNeverReferencesRowsOutsideCurrentAdapter() {
        assertEquals(listOf(0..1), TcpRowBatch.ranges(listOf(0, 1, 50), rowCount = 2))
        assertTrue(TcpRowBatch.ranges(listOf(8, 9), rowCount = 8).isEmpty())
        assertEquals(listOf(0..1, 4..4),
            TcpRowBatch.ranges(listOf(0, 1, 4, 5), rowCount = 5))
    }
}
