package com.v2ray.ang.util

/**
 * Selects the current underlying network within one VpnService callback epoch.
 * Stale callbacks and already-queued UI/Binder publications must not override
 * a newer network or resurrect a stopped service. No Android dependencies.
 */
internal class UnderlyingNetworkOwner<T : Any> {
    data class Update<T : Any>(val epoch: Long, val sequence: Long, val network: T?)

    private var epoch = 0L
    private var sequence = 0L
    private var running = false
    private var current: T? = null

    @Synchronized
    fun begin(): Long {
        epoch++
        sequence++
        running = true
        current = null
        return epoch
    }

    @Synchronized
    fun onAvailable(callbackEpoch: Long, network: T): Update<T>? {
        if (!running || callbackEpoch != epoch || current == network) return null
        current = network
        return Update(epoch, ++sequence, network)
    }

    /** Capabilities do not change the underlying network identity. */
    @Synchronized
    fun onCapabilitiesChanged(callbackEpoch: Long, network: T): Update<T>? {
        if (!running || callbackEpoch != epoch || current != network) return null
        return null // Avoid duplicate Binder calls and stale upstream refreshes.
    }

    @Synchronized
    fun onLost(callbackEpoch: Long, network: T): Update<T>? {
        if (!running || callbackEpoch != epoch || current != network) return null
        current = null
        return Update(epoch, ++sequence, null)
    }

    /** Stopping is an epoch boundary even when nothing was registered. */
    @Synchronized
    fun stop(): Update<T> {
        epoch++
        running = false
        current = null
        return Update(epoch, ++sequence, null)
    }

    @Synchronized
    fun isCurrent(update: Update<T>): Boolean =
        update.epoch == epoch && update.sequence == sequence && update.network == current

    /**
     * Recheck and publish under one short monitor to defeat a race between a
     * scheduled main-thread event and a newer ConnectivityManager callback.
     */
    @Synchronized
    fun publishIfCurrent(update: Update<T>, publish: (T?) -> Unit): Boolean {
        if (!isCurrent(update)) return false
        publish(update.network)
        return true
    }
}
