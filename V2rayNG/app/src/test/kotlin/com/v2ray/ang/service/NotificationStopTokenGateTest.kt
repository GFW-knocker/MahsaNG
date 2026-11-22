package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification tokens fence only the notification-generated stop command. */
class NotificationStopTokenGateTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"

    @Test fun defaultStateDeniesAllStopTokens() {
        val gate = NotificationStopTokenGate()
        assertFalse(gate.accepts(null))
        assertFalse(gate.accepts(""))
        assertFalse(gate.accepts(first))
    }

    @Test fun matchingTokenIsTheOnlyAcceptedNotificationStop() {
        val gate = NotificationStopTokenGate()
        assertTrue(gate.arm(first))
        assertTrue(gate.accepts(first))
        assertFalse(gate.accepts(second))
        assertFalse(gate.accepts(null))
        assertFalse(gate.accepts(""))
    }

    @Test fun replacementNotificationRejectsPreviousToken() {
        val gate = NotificationStopTokenGate()
        assertTrue(gate.arm(first))
        assertTrue(gate.arm(second))
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test fun serviceHandoverOrNativeStopInvalidatesOldNotification() {
        val gate = NotificationStopTokenGate()
        assertTrue(gate.arm(first))
        gate.invalidate()
        assertFalse(gate.accepts(first))
        assertFalse(gate.accepts(null))
        assertTrue(gate.arm(second))
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test fun invalidTokensAndAccidentalReuseFailClosed() {
        val gate = NotificationStopTokenGate()
        for (invalid in listOf("", "short", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".uppercase(), first + "/other",
            "11111111-1111-0111-8111-111111111111",
            "11111111-1111-4111-0111-111111111111")
        ) {
            assertFalse(gate.arm(invalid))
            assertFalse(gate.accepts(invalid))
        }
        assertTrue(gate.arm(first))
        assertFalse(gate.arm(first))
        gate.invalidate()
        assertFalse(gate.arm(first))
        assertTrue(gate.arm(second))
    }

    @Test fun invalidRotationDoesNotDiscardCurrentValidToken() {
        val gate = NotificationStopTokenGate()
        assertTrue(gate.arm(first))
        assertFalse(gate.arm("malformed"))
        assertTrue(gate.accepts(first))
    }
}
