package com.v2ray.ang.util

/** Nonblocking native index-lease policy; denied acquisitions never mutate state. */
internal object ProfileIndexMutationGate {
    inline fun <T> execute(
        unavailable: T,
        tryAcquire: () -> Boolean,
        release: () -> Unit,
        action: () -> T,
    ): T {
        if (!runCatching { tryAcquire() }.getOrDefault(false)) return unavailable
        return try {
            action()
        } finally {
            release()
        }
    }
}
