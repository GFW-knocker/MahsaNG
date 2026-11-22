package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortAndFragmentInputTest {
    @Test fun rejectsMissingAndNonTcpPortValues() {
        for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
            assertFalse("Accepted invalid TCP port $port", PortAndFragmentInput.validPort(port))
        }
        for (port in listOf(1, 443, 65535)) {
            assertTrue(PortAndFragmentInput.validPort(port))
            assertEquals(port, PortAndFragmentInput.requirePort(port))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotSilentlyReplaceAnInvalidPortWith443() {
        PortAndFragmentInput.requirePort(0)
    }

    @Test fun acceptsOnlyBoundedNondecreasingFragmentRanges() {
        for (input in listOf("1", "1-1", "10-20", " 12-36 ", "65535")) {
            assertTrue("Rejected supported range $input", PortAndFragmentInput.validFragmentRange(input))
        }
        for (input in listOf("", "-1", "0", "0-1", "10-5", "3-", "1.2",
            "10 - 20", "1-2-3", "65536", "999999999999999999", "x10")) {
            assertFalse("Accepted invalid range $input", PortAndFragmentInput.validFragmentRange(input))
        }
    }
}
