package com.v2ray.ang.service

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tracks per-client Java forwarding Threads and their owned sockets in ONE
 * fragmentor instance. A stopped listener is not proof its clients exited.
 *
 * Start registration and Thread.start() share the Stop monitor. Stop requests
 * socket closure, then an IO caller can join every registered Thread under one
 * bounded deadline. Neither a close request nor timeout is exit evidence.
 *
 * This does NOT certify native, VPN TUN, or cross-process session shutdown.
 */
internal class FragmentorForwardingWorkers {
    private val active = IdentityHashMap<Thread, MutableSet<Closeable>>()
    private var stopRequested = false

    private fun newResourceSet(): MutableSet<Closeable> =
        Collections.newSetFromMap(IdentityHashMap<Closeable, Boolean>())

    private fun closeQuietly(resource: Closeable) {
        runCatching { resource.close() }
    }

    // Avoid retaining every completed client Thread for the entire lifetime of
    // a busy listener. Close retired handles once more before dropping them.
    private fun pruneExitedLocked() {
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.key.isAlive) {
                entry.value.forEach { closeQuietly(it) }
                iterator.remove()
            }
        }
    }

    /** A rejected upstream/downstream worker must never be started by caller. */
    @Synchronized fun start(worker: Thread, vararg resources: Closeable): Boolean {
        if (stopRequested || worker.state != Thread.State.NEW) return false
        pruneExitedLocked()
        if (active.containsKey(worker)) return false
        val owned = newResourceSet()
        resources.forEach { owned.add(it) }
        active[worker] = owned
        try {
            worker.start()
        } catch (_: IllegalThreadStateException) {
            active.remove(worker)
            return false
        } catch (failure: Throwable) {
            active.remove(worker)
            throw failure
        }
        return true
    }

    /**
     * A backend socket can arrive after Stop (for example after a blocking
     * TCP connect). Close it immediately rather than publish a stale owner.
     */
    fun track(worker: Thread, resource: Closeable): Boolean {
        val accepted = synchronized(this) {
            if (stopRequested) false
            else active[worker]?.add(resource)?.let { true } ?: false
        }
        if (!accepted) closeQuietly(resource)
        return accepted
    }

    /** Idempotent, non-blocking stop request; sockets unblock most I/O. */
    fun stop() {
        val handles = synchronized(this) {
            stopRequested = true
            val unique = newResourceSet()
            active.values.forEach { unique.addAll(it) }
            unique.toList()
        }
        handles.forEach { closeQuietly(it) }
    }

    /**
     * Call only on an IO/background thread. Verify actual Thread termination
     * using join/isAlive; do not infer it from socket closure or a stop flag.
     */
    fun awaitExited(timeoutMillis: Long): Boolean {
        if (timeoutMillis !in 1L..10_000L) return false
        val deadlineNs = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val workers = synchronized(this) {
                if (!stopRequested) return false
                active.keys.toList()
            }
            if (workers.isEmpty()) return true
            for (worker in workers) {
                if (worker === Thread.currentThread()) return false
                if (worker.isAlive) {
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) return false
                    try {
                        worker.join(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                    if (worker.isAlive) return false
                }
                synchronized(this) { active.remove(worker) }
            }
        }
    }
}
