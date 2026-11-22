package com.v2ray.ang.service

import org.junit.Assert.*
import org.junit.Test

class VpnModeSelectionTest {
    @Test fun normalVpnNeedsConsentAndOwnService() {
        assertTrue(VpnModeSelection.requiresVpnPermission(null))
        assertTrue(VpnModeSelection.requiresVpnPermission(VpnModeSelection.VPN))
        assertFalse(VpnModeSelection.startsProxyProcess(null))
        assertFalse(VpnModeSelection.usesPsiphon(null))
    }
    @Test fun proxyOnlyNeverRequestsVpnAuthorization() {
        assertFalse(VpnModeSelection.requiresVpnPermission(VpnModeSelection.PROXY_ONLY))
        assertTrue(VpnModeSelection.startsProxyProcess(VpnModeSelection.PROXY_ONLY))
        assertFalse(VpnModeSelection.usesPsiphon(VpnModeSelection.PROXY_ONLY))
    }
    @Test fun psiphonIsOptInWithConsentAndProxyCore() {
        val mode = VpnModeSelection.PSIPHON_VIA_V2RAY
        assertTrue(VpnModeSelection.requiresVpnPermission(mode))
        assertTrue(VpnModeSelection.usesPsiphon(mode))
        assertTrue(VpnModeSelection.startsProxyProcess(mode))
    }
    @Test fun malformedPersistedModeFallsBackToVpnConsent() {
        assertTrue(VpnModeSelection.requiresVpnPermission("unrecognized"))
        assertFalse(VpnModeSelection.startsProxyProcess("unrecognized"))
    }
}
