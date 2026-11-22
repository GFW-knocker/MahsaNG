package com.v2ray.ang.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legacy fork profile editor must not silently discard stored settings. */
class RestoredProfileDtoTest {
    @Test fun wireguardPeerAndNoiseSettingsRoundTrip() {
        val settings = V2rayConfig.OutboundBean.OutSettingsBean(
            secretKey = "key",
            peers = listOf(V2rayConfig.OutboundBean.OutSettingsBean.WireGuardBean(
                publicKey = "public",
                endpoint = "example.org:443",
                preSharedKey = "shared",
                keepAlive = 5
            )),
            wnoise = "quic",
            wnoisecount = "10-15",
            wnoisedelay = "1",
            wpayloadsize = "5-10"
        )
        val restored = Gson().fromJson(Gson().toJson(settings), V2rayConfig.OutboundBean.OutSettingsBean::class.java)
        assertEquals("shared", restored.peers?.first()?.preSharedKey)
        assertEquals(5, restored.peers?.first()?.keepAlive)
        assertEquals("quic", restored.wnoise)
        assertEquals("10-15", restored.wnoisecount)
        assertEquals("1", restored.wnoisedelay)
        assertEquals("5-10", restored.wpayloadsize)
    }

    @Test fun fragmentAndMuxEditorStatesArePreserved() {
        val settings = V2rayConfig.OutboundBean.OutSettingsBean()
        settings.populateFragment(packets = "tlshello", length = "10-20", interval = "10-20")
        assertEquals("ON for TLS", settings.fragment?.status)
        assertEquals("tlshello", settings.fragment?.packets)
        assertEquals("10-20", settings.fragment?.length)

        settings.populateFragment(packets = "fakehost", fakehost_domain = "example.com")
        assertEquals("ON for non-TLS", settings.fragment?.status)
        assertEquals("example.com", settings.fragment?.host1_domain)

        val outbound = V2rayConfig.OutboundBean(protocol = "vless")
        outbound.populateMux(stat = "ON", concr = 4)
        assertTrue(outbound.mux!!.enabled)
        assertEquals("ON", outbound.mux!!.status)
        assertEquals(4, outbound.mux!!.concurrency)
        outbound.populateMux(stat = "OFF")
        assertFalse(outbound.mux!!.enabled)
        assertEquals("OFF", outbound.mux!!.status)
    }

    @Test fun echSettingsAndHysteriaEditorFieldsSerialize() {
        val tls = V2rayConfig.OutboundBean.StreamSettingsBean()
        tls.populateTlsSettings("tls", false, "example.org", null, null, null, null, null,
            echConfigList = "echconfig", echServerKeys = "key", echForceQuery = "full")
        assertEquals("echconfig", tls.tlsSettings?.echConfigList)
        assertEquals("key", tls.tlsSettings?.echServerKeys)
        assertEquals("full", tls.tlsSettings?.echForceQuery)

        val settings = V2rayConfig.OutboundBean.OutSettingsBean(
            portHopping = "443,2053",
            portHoppingInterval = "10",
            pinSHA256 = "pin"
        )
        val restored = Gson().fromJson(Gson().toJson(settings), V2rayConfig.OutboundBean.OutSettingsBean::class.java)
        assertEquals("443,2053", restored.portHopping)
        assertEquals("10", restored.portHoppingInterval)
        assertEquals("pin", restored.pinSHA256)
    }
}
