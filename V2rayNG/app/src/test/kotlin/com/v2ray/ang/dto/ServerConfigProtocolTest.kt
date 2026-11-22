package com.v2ray.ang.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServerConfigProtocolTest {
    @Test fun httpAndHysteria2UseServerShape() {
        for (protocol in listOf(EConfigType.HTTP, EConfigType.HYSTERIA2)) {
            val config = ServerConfig.create(protocol)
            val outbound = config.getProxyOutbound()
            assertNotNull(outbound)
            assertEquals(protocol.name.lowercase(), outbound?.protocol)
            assertEquals(1, outbound?.settings?.servers?.size)
        }
    }

    @Test fun mvlessUsesVlessWireProtocolAndVnextShape() {
        val config = ServerConfig.create(EConfigType.MVLESS)
        val outbound = config.getProxyOutbound()
        assertNotNull(outbound)
        assertEquals("vless", outbound?.protocol)
        assertEquals(1, outbound?.settings?.vnext?.size)
    }
}
