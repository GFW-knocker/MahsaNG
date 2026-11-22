package com.v2ray.ang.util

/**
 * A run-scoped default network identity with a non-recoverable handover latch.
 * An A -> B -> A handover cannot revive results from the original epoch.
 * Callback delivery and socket completion may occur on different threads.
 */
internal class NetworkHandoverGate<T : Any>(private val baseline: T?) {
    private var invalidated = baseline == null

    @Synchronized
    fun onAvailable(network: T) {
        if (network != baseline) invalidated = true
    }

    @Synchronized
    fun onLost(network: T) {
        if (network == baseline) invalidated = true
    }

    @Synchronized
    fun invalidate() {
        invalidated = true
    }

    @Synchronized
    fun accepts(currentNetwork: T?): Boolean =
        !invalidated && baseline != null && currentNetwork == baseline
}
