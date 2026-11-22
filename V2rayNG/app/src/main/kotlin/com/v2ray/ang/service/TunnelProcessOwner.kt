package com.v2ray.ang.service

/** Controls exactly one native child per VPN session, independent of Android lifecycle APIs. */
internal class TunnelProcessOwner<T : Any>(private val maxRestarts: Int = 5) {
    init { require(maxRestarts >= 0) }

    private var generation = 0L
    private var running = false
    private var active: T? = null
    private var restarts = 0

    /** Invalidates watchers from the preceding setup; returns its child to destroy. */
    @Synchronized fun begin(): Pair<Long, T?> {
        val previous = active
        active = null
        generation++
        running = true
        restarts = 0
        return generation to previous
    }

    /** Adopt only a child started by the current, still-running session. */
    @Synchronized fun adopt(token: Long, child: T): Boolean {
        if (!isCurrent(token) || active != null) return false
        active = child
        return true
    }

    /** True only for an owned child exiting while the session has restart budget. */
    @Synchronized fun departed(token: Long, child: T): Boolean {
        if (!isCurrent(token) || active !== child) return false
        active = null
        if (restarts >= maxRestarts) return false
        restarts++
        return true
    }

    @Synchronized fun isCurrent(token: Long): Boolean = running && token == generation

    /** The exact active native child must own FD handoff and its retry exhaustion. */
    @Synchronized fun owns(token: Long, child: T): Boolean =
        isCurrent(token) && active === child

    /** Prevent all subsequent adoption/restarts and return the child to terminate. */
    @Synchronized fun stop(): T? {
        generation++
        running = false
        val previous = active
        active = null
        return previous
    }
}
