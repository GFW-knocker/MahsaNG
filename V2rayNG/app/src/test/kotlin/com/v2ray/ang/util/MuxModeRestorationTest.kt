package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class MuxModeRestorationTest {
    @Test fun restoresEnabledMuxWithoutLegacyStatusAndPreservesConcurrencySelection() {
        assertEquals("ON", MuxModeRestoration.restore(status = null, enabled = true))
        assertEquals("OFF", MuxModeRestoration.restore(status = null, enabled = false))
    }

    @Test fun respectsExplicitStatusEvenWhenEnabledFlagDisagrees() {
        assertEquals("ON", MuxModeRestoration.restore(status = "ON", enabled = false))
        assertEquals("OFF", MuxModeRestoration.restore(status = "OFF", enabled = true))
    }

    @Test fun leavesModeUnsetOnlyWhenNoMuxWasSaved() {
        assertEquals("", MuxModeRestoration.restore(status = null, enabled = null))
    }
}
