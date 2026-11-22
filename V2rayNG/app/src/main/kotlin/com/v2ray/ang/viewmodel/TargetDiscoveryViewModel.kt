package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.util.TargetDiscoveryResult
import com.v2ray.ang.util.TargetProbeRoute

/**
 * Ephemeral scan evidence survives rotation but never backs up or auto-restarts.
 * The Activity owns and closes the actual sockets and network observer.
 */
internal class TargetDiscoveryViewModel : ViewModel() {
    val observations = mutableListOf<TargetDiscoveryResult>()
    var generation = 0L
    var running = false
    var stopped = false
    var networkChanged = false
    var total = 0
    var lastStartMillis = 0L
    var verifiedOnly = false
    var lastModeTls = false
    var requestedPasses = 1
    var lastRoute = TargetProbeRoute.APP_NETWORK
    var proxyUnavailable = false

    fun interrupt() {
        generation++
        running = false
        stopped = true
    }
}
