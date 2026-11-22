package com.v2ray.ang.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import java.io.Closeable

/**
 * Per-run OS callback lease. Snapshotting activeNetwork twice misses A -> B -> A;
 * an old run must remain invalid after ANY observed default-network handover.
 *
 * Android 5.x has no public activeNetwork identity: refuse rather than treating
 * untracked network data as valid. A registration failure also fails closed.
 * Close is idempotent and never affects the app's global VPN connection.
 */
internal class RunNetworkObserver private constructor(
    private val connectivity: ConnectivityManager?,
    private val gate: NetworkHandoverGate<Network>,
    private val callback: ConnectivityManager.NetworkCallback?,
) : Closeable {
    private val closeLock = Any()
    @Volatile private var closed = false

    fun isCurrent(): Boolean {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val current = runCatching { connectivity?.activeNetwork }.getOrNull()
        return gate.accepts(current)
    }

    override fun close() {
        val shouldUnregister = synchronized(closeLock) {
            if (closed) false
            else {
                closed = true
                gate.invalidate()
                true
            }
        }
        val leasedCallback = callback
        if (shouldUnregister && leasedCallback != null) {
            runCatching { connectivity?.unregisterNetworkCallback(leasedCallback) }
        }
    }

    companion object {
        fun register(context: Context): RunNetworkObserver {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // On Android 6, generic callbacks include unrelated networks.
            // Only default-network callbacks (API 24+) support reliable epochs.
            val baseline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { cm?.activeNetwork }.getOrNull()
            } else null
            val gate = NetworkHandoverGate(baseline)
            if (cm == null || baseline == null) return RunNetworkObserver(cm, gate, null)

            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    gate.onAvailable(network)
                }

                override fun onLost(network: Network) {
                    gate.onLost(network)
                }

                override fun onUnavailable() {
                    gate.invalidate()
                }
            }
            val registered = runCatching {
                cm.registerDefaultNetworkCallback(observer)
                true
            }.getOrDefault(false)
            if (!registered) gate.invalidate()
            return RunNetworkObserver(cm, gate, if (registered) observer else null)
        }
    }
}
