package com.v2ray.ang.util

import java.util.Locale

/** A TCP socket connect is meaningful only for a TCP-capable outbound.
 * Skipped UDP/custom profiles remain UNKNOWN rather than being marked failed. */
internal object TcpProbeEligibility {
    private val tcpNetworks = setOf("tcp", "ws", "httpupgrade", "xhttp", "splithttp", "h2", "grpc")
    private val udpProtocols = setOf("wireguard", "hysteria", "hysteria2", "tuic", "quic")
    private val unsupportedTypes = setOf("CUSTOM", "WIREGUARD", "HYSTERIA2")

    fun supportsTcpConnect(configType: String, protocol: String?, network: String?): Boolean {
        if (configType.uppercase(Locale.ROOT) in unsupportedTypes) return false
        val proto = protocol?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return false
        if (proto in udpProtocols) return false
        val transport = network?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "tcp"
        return transport in tcpNetworks
    }
}
