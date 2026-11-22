package com.v2ray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PsiphonRouteGateTest {
    @Test fun portAfterConnectedStartsRouteOnce() {
        val gate = PsiphonRouteGate()
        assertSame(PsiphonRouteAction.Wait, gate.onConnected())
        assertEquals(PsiphonRouteAction.Start(10809), gate.onSocksPort(10809))
        assertSame(PsiphonRouteAction.Wait, gate.onConnected())
        assertSame(PsiphonRouteAction.Wait, gate.onSocksPort(10809))
    }

    @Test fun connectedAfterPortStartsRouteOnce() {
        val gate = PsiphonRouteGate()
        assertSame(PsiphonRouteAction.Wait, gate.onSocksPort(10809))
        assertEquals(PsiphonRouteAction.Start(10809), gate.onConnected())
        assertSame(PsiphonRouteAction.Wait, gate.onConnected())
    }

    @Test fun stopRejectsDelayedCallbacks() {
        val gate = PsiphonRouteGate()
        gate.onConnected()
        gate.stop()
        assertSame(PsiphonRouteAction.Wait, gate.onSocksPort(10809))
        assertSame(PsiphonRouteAction.Wait, gate.onConnected())
    }

    @Test fun rejectsInvalidProxyPorts() {
        val gate = PsiphonRouteGate()
        gate.onConnected()
        assertSame(PsiphonRouteAction.Wait, gate.onSocksPort(0))
        assertSame(PsiphonRouteAction.Wait, gate.onSocksPort(65536))
    }

    @Test fun portChangeAfterRoutingMustFailClosed() {
        val gate = PsiphonRouteGate()
        gate.onSocksPort(10809)
        gate.onConnected()
        assertSame(PsiphonRouteAction.RestartRequired, gate.onSocksPort(10810))
    }
}
