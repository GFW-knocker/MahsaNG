package com.v2ray.ang.service

/** Unknown persisted modes require VPN authorization and revert to standard VPN. */
internal object VpnModeSelection {
    const val VPN = "VPN"
    const val PROXY_ONLY = "Proxy only"
    const val PSIPHON_VIA_V2RAY = "Psiphon via V2Ray"

    fun usesPsiphon(mode: String?): Boolean = mode == PSIPHON_VIA_V2RAY
    fun startsProxyProcess(mode: String?): Boolean = mode == PROXY_ONLY || usesPsiphon(mode)
    fun requiresVpnPermission(mode: String?): Boolean = mode != PROXY_ONLY
}
