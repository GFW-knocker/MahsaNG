package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class TargetDiscoveryProbeTest {
    @Test fun stoppedLeaseClosesOpenSocketsAndRejectsLateRegistration() {
        val lease = TargetSocketLease()
        val open = Socket()
        assertTrue(lease.register(open))
        assertTrue(lease.isActive())
        lease.stop()
        assertTrue(open.isClosed)
        assertFalse(lease.isActive())
        val late = Socket()
        assertFalse(lease.register(late))
        assertTrue(late.isClosed)
        lease.stop()
    }

    @Test fun successfulTcpConnectIsReachabilityOnlyAndDoesNotInventTlsEvidence() {
        ServerSocket(0).use { server ->
            val worker = Thread { server.accept().use { Thread.sleep(75) } }
            worker.start()
            val target = TargetDiscoveryPlanner.plan("127.0.0.1", server.localPort, false).single()
            val result = TargetDiscoveryProbe.inspect(target, 1000, TargetSocketLease())
            assertEquals(TargetProbeStatus.TCP_OPEN, result.status)
            assertNotNull(result.tcpElapsedMs)
            assertNull(result.tlsElapsedMs)
            assertFalse(result.target.verifyTls)
            assertNull(result.target.sni)
            worker.join(2000)
        }
    }

    @Test fun stoppedLeasePerformsNoNetworkConnect() {
        val lease = TargetSocketLease()
        lease.stop()
        val target = TargetDiscoveryPlanner.plan("127.0.0.1", 1, false).single()
        val result = TargetDiscoveryProbe.inspect(target, 1000, lease)
        assertEquals(TargetProbeStatus.CANCELLED, result.status)
        assertNull(result.tcpElapsedMs)
    }

    @Test fun rawTcpListenerCannotBeMistakenForTlsVerified() {
        ServerSocket(0).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 1500
                    Thread.sleep(650)
                }
            }
            worker.start()
            val target = TargetDiscoveryPlanner.plan(
                "127.0.0.1", server.localPort, true, "example.org",
            ).single()
            val result = TargetDiscoveryProbe.inspect(target, 400, TargetSocketLease())
            assertTrue(result.status != TargetProbeStatus.TLS_VERIFIED)
            assertNotNull(result.tcpElapsedMs)
            worker.join(2000)
        }
    }

    @Test fun invalidTimeoutRejectedBeforeAnySocketIsCreated() {
        val target = TargetDiscoveryPlanner.plan("example.org", 443, false).single()
        for (bad in listOf(0, -1, 20_000)) {
            try {
                TargetDiscoveryProbe.inspect(target, bad, TargetSocketLease())
                throw AssertionError("accepted invalid timeout")
            } catch (_: IllegalArgumentException) {
                // Deliberately no network access.
            }
        }
    }
}
