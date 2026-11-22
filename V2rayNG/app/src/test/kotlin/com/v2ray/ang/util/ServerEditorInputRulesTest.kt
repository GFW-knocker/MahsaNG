package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class ServerEditorInputRulesTest {
    @Test fun acceptsSingleValueAndIncreasingFragmentRange() {
        for (input in listOf("1", "1-1", "10-20", "65535", " 5-10 ")) {
            assertTrue(input, ServerEditorInputRules.validPositiveRange(input))
        }
    }

    @Test fun rejectsMalformedReversedAndOverflowingRanges() {
        for (input in listOf("", " ", "0", "-1", "5-3", "1-0", "1-2-3",
            "abc", "1.2", "1--2", "9999999999999999", "65536")) {
            assertFalse(input, ServerEditorInputRules.validPositiveRange(input))
        }
    }

    @Test fun validatesActualTransportPortLimits() {
        for (port in listOf(1, 443, 65535)) {
            assertTrue(ServerEditorInputRules.validPort(port))
            assertEquals(port, ServerEditorInputRules.requirePort(port))
        }
        for (port in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            assertFalse(ServerEditorInputRules.validPort(port))
            try {
                ServerEditorInputRules.requirePort(port)
                fail("Accepted invalid transport port $port")
            } catch (_: IllegalArgumentException) {
                // Invalid values must not silently wrap into valid ports.
            }
        }
    }
}
