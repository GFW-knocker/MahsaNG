package com.v2ray.ang.service

/** Bounded, local observation of the fragmentor listener Threads only.
 * This does not prove client handlers, tun2socks, Xray, or VPN TUN have exited.
 */
internal object FragmentorListenerExit {
    private const val MAX_WAIT_MS = 10_000L

    fun allExitedWithin(timeoutMillis: Long, vararg listeners: Thread?): Boolean {
        // Thread.join(0) waits forever. Refuse invalid and unbounded waits.
        if (timeoutMillis !in 1L..MAX_WAIT_MS) return false
        val startNs = System.nanoTime()
        for (listener in listeners) {
            if (listener == null) continue
            if (listener === Thread.currentThread()) return false
            if (!listener.isAlive) continue
            // Use one global deadline, not one full timeout per listener.
            val remainingNs = timeoutMillis * 1_000_000L - (System.nanoTime() - startNs)
            if (remainingNs <= 0L) return false
            try {
                listener.join(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (listener.isAlive) return false
        }
        return true
    }
}
