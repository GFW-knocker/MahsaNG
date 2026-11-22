package com.v2ray.ang.service

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Per-VpnService accounting for owned TUN descriptors.
 *
 * Begin before Builder.establish(); register even when Stop raced a late
 * establishment. Only a successful close() return for that exact owner may
 * call closeReturned(). A close request or an elapsed timeout proves nothing.
 *
 * This is NOT an authoritative VPN session/native-alias shutdown receipt.
 */
internal class TunDescriptorCloseTracker<T : Any> {
    private val outstanding: MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var establishmentsInFlight = 0
    private var stopRequested = false

    /** Refuse any new TUN establishment once this service begins teardown. */
    @Synchronized fun beginEstablish(): Boolean {
        if (stopRequested) return false
        establishmentsInFlight++
        return true
    }

    /** Register the exact returned descriptor, including a late rejected one. */
    @Synchronized fun registered(descriptor: T): Boolean {
        check(establishmentsInFlight > 0) { "No outstanding TUN establishment" }
        outstanding.add(descriptor)
        establishmentsInFlight--
        return !stopRequested
    }

    /** A Builder.establish() failure must not strand an in-flight reservation. */
    @Synchronized fun establishFailed() {
        check(establishmentsInFlight > 0) { "No outstanding TUN establishment" }
        establishmentsInFlight--
    }

    /** Never confuse equal descriptor attributes with the same FD owner. */
    @Synchronized fun isOutstanding(descriptor: T): Boolean = outstanding.contains(descriptor)

    /** The owner calls this ONLY after ParcelFileDescriptor.close() succeeds. */
    @Synchronized fun closeReturned(descriptor: T): Boolean = outstanding.remove(descriptor)

    /** A repeated Stop must preserve all unclosed handles and reservations. */
    @Synchronized fun requestStop(): List<T> {
        stopRequested = true
        return outstanding.toList()
    }

    /** Nonblocking snapshot for an Android lifecycle callback (not native FD proof). */
    @Synchronized fun isStopComplete(): Boolean =
        stopRequested && establishmentsInFlight == 0 && outstanding.isEmpty()

    /**
     * Reopen for a fresh session on a reused VpnService instance ONLY after
     * every tracked establishment and descriptor has already closed cleanly.
     * Any pending establishment or unclosed descriptor fails closed.
     */
    @Synchronized fun resetForNewSession(): Boolean {
        if (!stopRequested) return true
        if (establishmentsInFlight != 0 || outstanding.isNotEmpty()) return false
        stopRequested = false
        return true
    }

    /** Bounded observation for IO cleanup; never block an Android lifecycle callback. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        if (timeoutMillis !in 1L..10_000L) return false
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val complete = isStopComplete()
            if (complete) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return false
            try {
                Thread.sleep(minOf(20L, maxOf(1L, remaining / 1_000_000L)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}
