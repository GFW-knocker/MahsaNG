package com.v2ray.ang.util

/** Deterministic, stable ordering from one immutable snapshot of the displayed profiles. */
internal object ProfileDelayOrder {
    fun order(guids: Collection<String>, readDelay: (String) -> Long?): List<String> {
        // Source may be a mutable UI cache that is refreshed during a test.
        val guidsSnapshot = guids.toList()
        if (guidsSnapshot.isEmpty()) return emptyList()
        val delays = guidsSnapshot.distinct().associateWith { readDelay(it) ?: 0L }
        return guidsSnapshot.withIndex().sortedWith(
            compareBy<IndexedValue<String>>(
                { if ((delays[it.value] ?: 0L) > 0) 0 else 1 },
                { (delays[it.value] ?: 0L).takeIf { value -> value > 0L } ?: Long.MAX_VALUE },
                { it.index },
            ),
        ).map { it.value }
    }
}
