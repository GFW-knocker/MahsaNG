package com.v2ray.ang.dto

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ExtendedServerFieldsTest {
    private val gson = Gson()

    @Test fun wireGuardExtendedFieldsSurviveProfileRoundTrip() {
        val peer = V2rayConfig.OutboundBean.OutSettingsBean.WireGuardBean(
            publicKey = "public", endpoint = "host.example:51820",
            preSharedKey = "psk", keepAlive = 25,
        )
        val settings = V2rayConfig.OutboundBean.OutSettingsBean(
            secretKey = "private",
            peers = listOf(peer),
            wnoise = "quic", wnoisecount = "2-3",
            wnoisedelay = "10-20", wpayloadsize = "16-32",
        )
        val restored = gson.fromJson(
            gson.toJson(settings), V2rayConfig.OutboundBean.OutSettingsBean::class.java,
        )
        assertEquals("psk", restored.peers?.first()?.preSharedKey)
        assertEquals(25, restored.peers?.first()?.keepAlive)
        assertEquals("quic", restored.wnoise)
        assertEquals("2-3", restored.wnoisecount)
        assertEquals("10-20", restored.wnoisedelay)
        assertEquals("16-32", restored.wpayloadsize)
    }

    @Test fun editorFragmentChoicesRoundTripWithoutChangingUnrelatedSettings() {
        val settings = V2rayConfig.OutboundBean.OutSettingsBean(
            servers = listOf(V2rayConfig.OutboundBean.OutSettingsBean.ServersBean(address = "example.org")),
        )
        settings.populateFragment(packets = "tlshello", length = "10-20", interval = "5-10")
        assertEquals("ON for TLS", settings.fragment?.status)
        assertEquals("tlshello", settings.fragment?.packets)
        settings.populateFragment(packets = "fakehost", fakehost_domain = "example.com")
        assertEquals("ON for non-TLS", settings.fragment?.status)
        assertEquals("example.com", settings.fragment?.host1_domain)
        settings.populateFragment(packets = "1-3", length = "3-5", interval = "5-10")
        assertEquals("ON for generic", settings.fragment?.status)
        val restored = gson.fromJson(gson.toJson(settings), V2rayConfig.OutboundBean.OutSettingsBean::class.java)
        assertEquals("ON for generic", restored.fragment?.status)
        assertEquals("1-3", restored.fragment?.packets)
        assertEquals("example.org", restored.servers?.first()?.address)
    }

    @Test fun muxStateCorrespondsToEnabledCoreConfiguration() {
        val outbound = V2rayConfig.OutboundBean(protocol = "vless")
        outbound.populateMux(stat = "ON", concr = 16)
        assertTrue(outbound.mux!!.enabled)
        assertEquals(16, outbound.mux!!.concurrency)
        assertEquals("ON", outbound.mux!!.status)
        outbound.populateMux(stat = "OFF")
        assertFalse(outbound.mux!!.enabled)
        assertEquals("OFF", outbound.mux!!.status)
        outbound.populateMux(stat = "")
        assertNull(outbound.mux)
        for (invalid in listOf(0, -1)) {
            try {
                outbound.populateMux(stat = "ON", concr = invalid)
                fail("Invalid mux concurrency was silently corrected")
            } catch (_: IllegalArgumentException) {
                // A malformed editor value must not be made plausible by clamping.
            }
        }
        try {
            outbound.populateMux(stat = "UNKNOWN")
            fail("Unknown mux mode silently changed the user's settings")
        } catch (_: IllegalArgumentException) {
            // Only the three modes in gfw_mux are supported.
        }
    }

    @Test fun tlsEchValuesSurviveProfileRoundTrip() {
        val stream = V2rayConfig.OutboundBean.StreamSettingsBean()
        stream.populateTlsSettings(
            streamSecurity = "tls", allowInsecure = false, sni = "host.example",
            fingerprint = "chrome", alpns = "h2,http/1.1",
            publicKey = null, shortId = null, spiderX = null,
            echConfigList = "ech-cfg", echServerKeys = "ech-keys",
            echForceQuery = "true",
        )
        val restored = gson.fromJson(
            gson.toJson(stream), V2rayConfig.OutboundBean.StreamSettingsBean::class.java,
        )
        assertEquals("ech-cfg", restored.tlsSettings?.echConfigList)
        assertEquals("ech-keys", restored.tlsSettings?.echServerKeys)
        assertEquals("true", restored.tlsSettings?.echForceQuery)
        assertNull(restored.realitySettings)
    }

    @Test fun switchingFromTlsToRealityDropsUnsupportedEchValues() {
        val stream = V2rayConfig.OutboundBean.StreamSettingsBean()
        stream.populateTlsSettings(
            streamSecurity = "reality", allowInsecure = false, sni = "host.example",
            fingerprint = "chrome", alpns = null,
            publicKey = "public", shortId = "abc", spiderX = "/",
            echConfigList = "stale-ech-config", echServerKeys = "stale-private-keys",
            echForceQuery = "true",
        )
        assertNull(stream.tlsSettings)
        assertNotNull(stream.realitySettings)
        assertNull(stream.realitySettings?.echConfigList)
        assertNull(stream.realitySettings?.echServerKeys)
        assertNull(stream.realitySettings?.echForceQuery)
    }

    @Test fun hysteriaEditorFieldsRoundTrip() {
        val settings = V2rayConfig.OutboundBean.OutSettingsBean(
            portHopping = "2000-3000",
            portHoppingInterval = "30",
            pinSHA256 = "hash",
        )
        val restored = gson.fromJson(gson.toJson(settings), V2rayConfig.OutboundBean.OutSettingsBean::class.java)
        assertEquals("2000-3000", restored.portHopping)
        assertEquals("30", restored.portHoppingInterval)
        assertEquals("hash", restored.pinSHA256)
    }
}
