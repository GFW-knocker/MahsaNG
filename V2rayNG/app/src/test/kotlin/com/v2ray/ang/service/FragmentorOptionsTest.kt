package com.v2ray.ang.service

import org.junit.Test
import org.junit.Assert.assertEquals

class FragmentorOptionsTest {
    @Test fun disabledSettingsDoNotAllocateFragmentor() {
        val options = FragmentorOptions.fromSettings("node.example", 443) { _, default -> default }
        assertEquals(null, options)
    }

    @Test fun realSettingsSupplyFragmentationParameters() {
        val prefs = mapOf("sw_frag" to "true", "nfrag" to "90", "sleepfrag" to "0.007", "config_ip_checkbox" to "true")
        val options = FragmentorOptions.fromSettings("node.example", 443) { key, default -> prefs[key] ?: default }!!
        assertEquals("node.example", options.targetHost)
        assertEquals(443, options.targetPort)
        assertEquals(90, options.fragments)
        assertEquals(0.007, options.delaySeconds, 1e-9)
    }

    @Test fun manualIpOverrideFollowsTheSettingsToggle() {
        val prefs = mapOf("sw_frag" to "true", "ip_checkbox" to "true", "manual_ip" to "203.0.113.5")
        val options = FragmentorOptions.fromSettings("node.example", 443) { key, default -> prefs[key] ?: default }!!
        assertEquals("203.0.113.5", options.targetHost)
    }

    @Test fun invalidSettingsDoNotStartDangerousOrLoopbackRelay() {
        val invalid = mapOf("sw_frag" to "true", "nfrag" to "-3")
        assertEquals(null, FragmentorOptions.fromSettings("node.example", 443) { key, default -> invalid[key] ?: default })
        assertEquals(null, FragmentorOptions.fromSettings("127.0.0.1", 2500) { key, default -> if (key == "sw_frag") "true" else default })
        assertEquals(null, FragmentorOptions.fromSettings("node.example", -1) { key, default -> if (key == "sw_frag") "true" else default })
    }
}
