package com.v2ray.ang.service

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Local accounting for every native child launched by ONE VpnService instance.
 *
 * register() happens before process adoption; watcher waitFor() must call
 * observedExit() only after the exact subprocess terminates. A late spawn
 * remains outstanding even if Stop won before adoption.
 *
 * Not a TUN/packet-path proof or an inter-process generation receipt.
 */
internal class TunnelProcessExitTracker<T : Any> {
    private val children: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var launchesInFlight = 0
    private var stopRequested = false

    /** Must precede any blocking ProcessBuilder.start(). */
    @Synchronized fun beginLaunch(): Boolean {
        if (stopRequested) return false
        launchesInFlight++
        return true
    }

    /** Always record a successfully spawned child, including a late rejected one. */
    @Synchronized fun registered(child: T): Boolean {
        check(launchesInFlight > 0) { "No outstanding tunnel launch" }
        children.add(child)
        launchesInFlight--
        return !stopRequested
    }

    @Synchronized fun launchFailed() {
        check(launchesInFlight > 0) { "No outstanding tunnel launch" }
        launchesInFlight--
    }

    /** True only for the same Process instance previously recorded. */
    @Synchronized fun observedExit(child: T): Boolean = children.remove(child)

    /** Idempotently block further launches. Return all children to signal termination. */
    @Synchronized fun requestStop(): List<T> {
        stopRequested = true
        return children.toList()
    }

    /**
     * Reopen for a fresh session on a reused VpnService instance ONLY when
     * no launches remain in flight and every tracked child has exited.
     */
    @Synchronized fun resetForNewSession(): Boolean {
        if (!stopRequested) return true
        if (launchesInFlight != 0 || children.isNotEmpty()) return false
        stopRequested = false
        return true
    }

    /**
     * Bounded wait on a background/IO thread. No Android API-26 Process
     * polling methods; receipt comes from the existing waitFor() watcher.
     * Never treat timeout or interruption as native termination evidence.
     */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        if (timeoutMillis !in 1L..10_000L) return false
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val complete = synchronized(this) {
                stopRequested && launchesInFlight == 0 && children.isEmpty()
            }
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
