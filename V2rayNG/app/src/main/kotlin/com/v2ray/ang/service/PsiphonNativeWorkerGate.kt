package com.v2ray.ang.service

/**
 * Process-wide JNI preparation gate. Only the worker that acquired the exact
 * lease may release it; a delayed/duplicate completion must never unlock a
 * different worker's native routing state.
 *
 * A Lease is a process-local identity token, NOT JNI completion evidence,
 * an OS process identity, or a cross-process VPN session receipt.
 */
internal object PsiphonNativeWorkerGate {
    // Identity, not equality or a reusable Boolean. Callers cannot construct
    // the active instance: even a forged Lease cannot release another owner.
    internal class Lease internal constructor()

    private var activeLease: Lease? = null

    /**
     * Under the same lock, reserve the worker and prepare native state.
     * On failed preparation there is no live worker to retain the lease.
     * A rejected attempt NEVER calls prepareNative().
     */
    @Synchronized
    fun acquire(prepareNative: () -> Unit): Lease? {
        if (activeLease != null) return null
        val lease = Lease()
        activeLease = lease
        try {
            prepareNative()
        } catch (failure: Throwable) {
            if (activeLease === lease) activeLease = null
            throw failure
        }
        return lease
    }

    /**
     * Release only this attempt's lease. Stale, duplicate and foreign callers
     * cannot erase a successor's active JNI worker reservation.
     */
    @Synchronized
    fun release(lease: Lease): Boolean {
        if (activeLease !== lease) return false
        activeLease = null
        return true
    }
}
