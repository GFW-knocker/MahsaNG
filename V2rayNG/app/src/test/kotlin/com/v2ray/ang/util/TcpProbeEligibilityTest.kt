package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpProbeEligibilityTest {
    @Test fun ordinaryTcpWebsocketGrpcProxiesAreEligible() {
        assertTrue(TcpProbeEligibility.supportsTcpConnect("VMESS", "vmess", "tcp"))
        assertTrue(TcpProbeEligibility.supportsTcpConnect("VLESS", "vless", "ws"))
        assertTrue(TcpProbeEligibility.supportsTcpConnect("TROJAN", "trojan", "grpc"))
        assertTrue(TcpProbeEligibility.supportsTcpConnect("VMESS", "vmess", null))
    }

    @Test fun udpOnlyProfilesCannotBeMisreportedAsFailedTcpConnections() {
        assertFalse(TcpProbeEligibility.supportsTcpConnect("WIREGUARD", "wireguard", null))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("HYSTERIA2", "hysteria2", null))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("VLESS", "tuic", "tcp"))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("VMESS", "vmess", "quic"))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("VLESS", "vless", "kcp"))
    }

    @Test fun customAndUnknownTransportAreNotAssumedToUseTcp() {
        assertFalse(TcpProbeEligibility.supportsTcpConnect("CUSTOM", "vmess", "tcp"))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("VLESS", "vless", "unexpected"))
        assertFalse(TcpProbeEligibility.supportsTcpConnect("VMESS", "", "tcp"))
    }
}
