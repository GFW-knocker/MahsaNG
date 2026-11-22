package com.v2ray.ang.service

/**
 * Fences notification-generated Stop actions to their current issuer.
 *
 * The token is a volatile, per-process notification identity, NOT a shared
 * VPN session lease or an authentication credential. UI stop broadcasts
 * without a notification URI are deliberately handled by their legacy path.
 */
internal class NotificationStopTokenGate {
    private val canonicalUuid =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private var active: String? = null
    private var lastIssued: String? = null

    /** Rotate whenever a new notification/session owner is published. */
    @Synchronized
    fun arm(token: String): Boolean {
        if (!canonicalUuid.matches(token) || token == lastIssued) return false
        lastIssued = token
        active = token
        return true
    }

    /** A service replacement or stop invalidates all earlier notification actions. */
    @Synchronized
    fun invalidate() {
        active = null
    }

    /** Missing, invalid, or previous-session notification actions are rejected. */
    @Synchronized
    fun accepts(token: String?): Boolean =
        token != null && active != null && active == token
}
