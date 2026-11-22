package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetDiscoveryPlannerTest {
    @Test fun expandsIpv4CidrWithoutSkippingNetworkOrBroadcast() {
        val targets = TargetDiscoveryPlanner.plan("198.51.100.4/30", 443, verifyTls = false)
        assertEquals(listOf("198.51.100.4", "198.51.100.5", "198.51.100.6", "198.51.100.7"),
            targets.map { it.host })
        assertTrue(targets.all { it.source == TargetSource.IPV4_CIDR && it.sni == null })
    }

    @Test fun expandsInclusiveRangeAndDeduplicatesPreservingOrder() {
        val targets = TargetDiscoveryPlanner.plan(
            "203.0.113.4-203.0.113.6, 203.0.113.5; example.org, EXAMPLE.org", 8443, false
        )
        assertEquals(listOf("203.0.113.4", "203.0.113.5", "203.0.113.6", "example.org"),
            targets.map { it.host })
        assertEquals(TargetSource.IPV4_RANGE, targets[0].source)
        assertEquals(TargetSource.HOSTNAME, targets.last().source)
        assertTrue(targets.all { it.port == 8443 })
    }

    @Test fun rejectsBroadCidrWithoutLaunchingAnyPartialScan() {
        for (input in listOf("0.0.0.0/0", "203.0.113.0/24", "203.0.113.0-203.0.114.0")) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetDiscoveryPlanner.plan(input, 443, false)
            }
        }
    }

    @Test fun allowsExactCapButRejectsOneAbove() {
        val valid = TargetDiscoveryPlanner.plan("198.51.100.0/25", 80, false)
        assertEquals(TargetDiscoveryPlanner.MAX_TARGETS, valid.size)
        assertThrows(IllegalArgumentException::class.java) {
            TargetDiscoveryPlanner.plan("198.51.100.0/25, 203.0.113.5", 80, false)
        }
    }

    @Test fun rejectsInvalidOrMixedTargetsAtomically() {
        for (input in listOf(
            "", "203.0.113.1, https://example.org", "203.0.113.5-203.0.113.1",
            "192.0.2.0/33", "999.1.1.1", "192.0.2.1:443", "example.org/path",
            "https://user:pass@example.org", "bad host", "example.org,invalid..domain",
        )) {
            assertThrows("should reject $input", IllegalArgumentException::class.java) {
                TargetDiscoveryPlanner.plan(input, 443, false)
            }
        }
        for (port in listOf(0, -1, 65536)) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetDiscoveryPlanner.plan("example.org", port, false)
            }
        }
    }

    @Test fun literalIpTlsRequiresUserSuppliedSni() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetDiscoveryPlanner.plan("198.51.100.7", 443, true)
        }
        val target = TargetDiscoveryPlanner.plan("198.51.100.7", 443, true, "Front.Example")[0]
        assertEquals("front.example", target.sni)
        assertEquals("198.51.100.7", target.host)
        assertTrue(target.verifyTls)
    }

    @Test fun dnsNameTlsUsesThatNameWhenNoOverrideIsSet() {
        val target = TargetDiscoveryPlanner.plan("Example.Org", 443, true).single()
        assertEquals("example.org", target.host)
        assertEquals("example.org", target.sni)
        assertEquals(TargetSource.HOSTNAME, target.source)
    }

    @Test fun configuredSniAppliesToAllTargetsAndMustBeAHostname() {
        val targets = TargetDiscoveryPlanner.plan("example.org, 198.51.100.1", 443, true, "TLS.EXAMPLE")
        assertTrue(targets.all { it.sni == "tls.example" })
        for (invalid in listOf("192.0.2.5", "https://example.org", "bad/path", "a..b")) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetDiscoveryPlanner.plan("198.51.100.7", 443, true, invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetDiscoveryPlanner.plan("example.org", 443, false, "unneeded.example")
        }
    }

    @Test fun ipv6IsSingleLiteralOnlyAndNeedsExplicitTlsSni() {
        val result = TargetDiscoveryPlanner.plan("2001:db8::1", 443, false)
        assertEquals(1, result.size)
        assertEquals(TargetSource.IPV6_LITERAL, result.single().source)
        assertTrue(result.single().host.contains(':'))
        assertThrows(IllegalArgumentException::class.java) {
            TargetDiscoveryPlanner.plan("2001:db8::/126", 443, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetDiscoveryPlanner.plan("[2001:db8::1]", 443, true)
        }
    }

    @Test fun malformedIpv4AndIPv6AreRejectedInsteadOfBeingParsedAsDomains() {
        for (bad in listOf("192.168.001.1", "1.2.3.999", "1.2.3", "1234::xyz", "fe80::1%eth0")) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetDiscoveryPlanner.plan(bad, 443, false)
            }
        }
    }

    @Test fun doesNotPerformNetworkResolutionWhilePlanning() {
        val targets = TargetDiscoveryPlanner.plan("unlikely.invalid.example", 443, true)
        assertEquals("unlikely.invalid.example", targets.single().host)
        assertEquals(TargetSource.HOSTNAME, targets.single().source)
    }
}
