package com.v2ray.ang.gfwknocker

import org.junit.Assert.assertEquals
import org.junit.Test

class GFWClientIpTest {
    @Test fun mapsValidIsoCountriesToLabels() {
        assertEquals("🇺🇸", GFW_client_ip.countryCodeToFlag("us"))
        assertEquals("United States", GFW_client_ip.countryCodeToName("US"))
    }

    @Test fun autoAndInvalidValuesNeverProduceFabricatedFlags() {
        for (value in listOf("Auto", "", "ZZ", "U", "US123", "12")) {
            assertEquals("", GFW_client_ip.countryCodeToFlag(value))
            assertEquals("", GFW_client_ip.countryCodeToName(value))
        }
    }
}
