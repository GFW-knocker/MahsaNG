package com.v2ray.ang.service

import org.junit.Assert.*
import org.junit.Test

class PsiphonProxyPortRulesTest {
    @Test fun defaultAutomaticPortsAreValid() {
        assertEquals(PsiphonProxyPorts(0, 0), PsiphonProxyPortRules.parse("0", "0"))
        assertTrue(PsiphonProxyPortRules.isValid("0", "10810"))
        assertTrue(PsiphonProxyPortRules.isValid("10811", "0"))
    }
    @Test fun adjacentHighPortsAreAllowed() {
        assertEquals(PsiphonProxyPorts(65534, 65533), PsiphonProxyPortRules.parse("65534", "65533"))
    }
    @Test fun rejectsInvalidAndOverflowPorts() {
        for (v in listOf("", "-1", "65535", "9999999999999999", "abc")) {
            assertFalse(v, PsiphonProxyPortRules.isValid(v, "0"))
        }
    }
    @Test fun rejectsReservedAndDuplicatedPorts() {
        assertFalse(PsiphonProxyPortRules.isValid("10808", "0"))
        assertFalse(PsiphonProxyPortRules.isValid("0", "10809"))
        assertFalse(PsiphonProxyPortRules.isValid("10810", "10810"))
    }
    @Test fun trimsWhitespaceBeforeParsing() {
        assertEquals(PsiphonProxyPorts(0, 10811), PsiphonProxyPortRules.parse(" 0 ", " 10811 "))
    }
}
