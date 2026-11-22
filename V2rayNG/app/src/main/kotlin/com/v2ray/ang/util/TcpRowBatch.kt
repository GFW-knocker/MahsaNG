package com.v2ray.ang.util

/**
 * Run-owned UI invalidation buffer. Completion writes stay protected by
 * TestRunTracker; only presentation events are deduplicated/coalesced here.
 */
internal class TcpRowBatch {
    private var ownerRunId: String? = null
    private val pending = LinkedHashSet<String>()

    @Synchronized
    fun begin(runId: String) {
        require(runId.isNotBlank())
        ownerRunId = runId
        pending.clear()
    }

    @Synchronized
    fun add(runId: String, guid: String): Boolean {
        if (runId != ownerRunId || guid.isBlank()) return false
        pending.add(guid)
        return true
    }

    /** Use the *current* displayed order, never stale indices from a prior filter. */
    @Synchronized
    fun drain(runId: String, visibleGuids: List<String>): List<Int> {
        if (runId != ownerRunId || pending.isEmpty()) return emptyList()
        val touched = pending.toSet()
        pending.clear()
        return visibleGuids.mapIndexedNotNull { index, guid -> index.takeIf { guid in touched } }
    }

    companion object {
        /** Convert many row events into minimal contiguous RecyclerView range notifications. */
        fun ranges(indices: List<Int>, rowCount: Int = Int.MAX_VALUE): List<IntRange> {
            val ordered = indices.filter { it >= 0 && it < rowCount }.distinct().sorted()
            if (ordered.isEmpty()) return emptyList()
            val output = mutableListOf<IntRange>()
            var first = ordered.first()
            var last = first
            ordered.drop(1).forEach { index ->
                if (index == last + 1) last = index
                else {
                    output.add(first..last)
                    first = index
                    last = index
                }
            }
            output.add(first..last)
            return output
        }
    }

    @Synchronized
    fun clear() {
        ownerRunId = null
        pending.clear()
    }
}
