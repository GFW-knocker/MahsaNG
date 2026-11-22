package com.v2ray.ang.dto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Numeric IDs of existing saved profile types must remain stable. */
class EConfigTypeTest {
    @Test fun retainsLegacyIdsAndSchemes() {
        val expected = mapOf(
            1 to EConfigType.VMESS,
            2 to EConfigType.CUSTOM,
            3 to EConfigType.SHADOWSOCKS,
            4 to EConfigType.SOCKS,
            5 to EConfigType.VLESS,
            6 to EConfigType.TROJAN,
            7 to EConfigType.WIREGUARD,
            9 to EConfigType.HYSTERIA2,
            10 to EConfigType.HTTP,
            11 to EConfigType.MVLESS
        )
        expected.forEach { (id, kind) ->
            assertEquals(id, kind.value)
            assertEquals(kind, EConfigType.fromInt(id))
        }
        assertEquals("vless://", EConfigType.MVLESS.protocolScheme)
        assertEquals("hysteria2://", EConfigType.HYSTERIA2.protocolScheme)
        assertEquals("http://", EConfigType.HTTP.protocolScheme)
    }

    @Test fun leavesUnusedTuicIdUnassigned() {
        assertEquals(null, EConfigType.fromInt(8))
    }
}
