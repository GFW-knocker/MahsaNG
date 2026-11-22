package com.v2ray.ang.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable batch snapshot with bounded, lock-free work claims.
 *
 * Only [workerCount] coroutines are launched by the caller, even for a large
 * subscription. Claims are unique across workers and never enqueue a coroutine
 * per target.
 */
internal class BoundedProbeQueue<T>(items: List<T>, concurrency: Int) {
    private val items = items.toList()
    private val nextIndex = AtomicInteger()

    val workerCount: Int = minOf(items.size, concurrency.coerceIn(1, MAX_CONCURRENCY))

    fun claim(): T? = items.getOrNull(nextIndex.getAndIncrement())

    companion object {
        const val DEFAULT_CONCURRENCY = 8
        const val MAX_CONCURRENCY = 16

        /** Invalid/missing settings fall back; numeric values remain capped. */
        fun concurrencyFromStored(value: String?): Int =
            (value?.toIntOrNull() ?: DEFAULT_CONCURRENCY).coerceIn(1, MAX_CONCURRENCY)
    }
}
