package com.v2ray.ang.viewmodel

import android.net.Network
import androidx.lifecycle.ViewModel
import com.v2ray.ang.util.dns.DnsProbeResult
import com.v2ray.ang.util.dns.DnsRoute

/**
 * One screen's ephemeral DNS evidence. Survives configuration changes, not process
 * death. An interrupted scan must preserve completed observations without retrying.
 */
internal class DnsDiagnosticsViewModel : ViewModel() {
    val results = mutableListOf<DnsProbeResult>()
    var filter = 0
    var fastestFirst = false
    var generation = 0L
    var completed = 0
    var wasStopped = false
    var total = 0
    var selectedRoute = DnsRoute.APP_UDP_TCP
    var lastRunRoute: DnsRoute? = null
    var lastRunSocksPort: Int? = null
    var runNetwork: Network? = null
    var runRouteHint: Int? = null
    var observedNetworkChange = false
    var lastRender = 0L
    var activeRun = false

    fun interruptActiveRun() {
        activeRun = false
        wasStopped = true
        generation++ // Invalidate late responses from the old Activity generation.
    }
}
