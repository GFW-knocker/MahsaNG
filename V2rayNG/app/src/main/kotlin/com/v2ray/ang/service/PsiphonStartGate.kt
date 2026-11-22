package com.v2ray.ang.service

/** Prevents duplicate VPN startups and invalidates workers on stop/revoke. */
internal class PsiphonStartGate {
    private var epoch = 0L
    private var active = false
    private var terminal = false

    @Synchronized fun beginStart(): Long? {
        if (active || terminal) return null
        epoch += 1
        active = true
        return epoch
    }

    @Synchronized fun isCurrent(token: Long): Boolean = active && !terminal && epoch == token

    @Synchronized fun isActive(): Boolean = active && !terminal

    @Synchronized fun stop() {
        epoch += 1
        active = false
        terminal = true
    }
}
