package com.v2ray.ang.util.dns

import org.junit.Assert.*
import org.junit.Test

class DnsResolverDefaultsTest {
    @Test fun prefillUsesConfiguredNumericResolversWithoutImplicitPublicFallback() {
        val observed = DnsResolverDefaults.fromConfiguredEntries(
            listOf("1.1.1.1", "1.1.1.1:53", "[2606:4700:4700::1111]:53")
        )
        assertEquals(2, observed.numericResolvers.size)
        assertTrue(observed.suggestedInput.contains("1.1.1.1:53"))
        assertTrue(observed.suggestedInput.contains("2606:4700:4700"))
        assertEquals(0, observed.unsupportedCount)
    }

    @Test fun confidentialDohSettingsAreNeverPrintedOrConvertedToPlainDns() {
        val secretUrl = "https://user:password@dns.example/dns-query?token=private"
        val observed = DnsResolverDefaults.fromConfiguredEntries(listOf(secretUrl))
        assertEquals("", observed.suggestedInput)
        assertEquals(1, observed.unsupportedCount)
        assertFalse(observed.toString().contains("password"))
        assertFalse(observed.toString().contains("private"))
    }

    @Test fun fourResolverInputCapPreservesCountOfAdditionalConfiguredIps() {
        val observed = DnsResolverDefaults.fromConfiguredEntries(
            listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1", "8.8.4.4")
        )
        assertEquals(5, observed.numericResolvers.size)
        assertEquals(4, observed.suggestedInput.split(",").size)
        assertEquals(1, observed.additionalNumericCount)
    }
}
