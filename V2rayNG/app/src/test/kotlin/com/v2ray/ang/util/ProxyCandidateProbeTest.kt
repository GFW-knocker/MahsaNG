package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProxyCandidateProbeTest {
    private fun testProxy(reply: String, operation: (Int) -> TargetDiscoveryResult): Pair<TargetDiscoveryResult, String> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = AtomicReference("")
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val request = StringBuilder()
                    var lastFour = ""
                    while (request.length < 8192 && !lastFour.endsWith("\r\n\r\n")) {
                        val byte = socket.getInputStream().read()
                        if (byte == -1) break
                        val next = byte.toChar()
                        request.append(next)
                        lastFour = (lastFour + next).takeLast(4)
                    }
                    received.set(request.toString())
                    socket.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
            worker.start()
            val result = operation(server.localPort)
            worker.join(3000)
            assertFalse("Fake proxy worker must finish", worker.isAlive)
            return result to received.get()
        }
    }

    @Test fun connectsToLocalProxyWithLiteralIpAndNeverDirectTarget() {
        val target = TargetDiscoveryPlanner.plan("198.51.100.7", 443, false).single()
        val (result, request) = testProxy("HTTP/1.1 200 Connection established\r\n\r\n") { port ->
            ProxyCandidateProbe.inspect(target, 1000, port, TargetSocketLease())
        }
        assertEquals(TargetProbeRoute.LOCAL_HTTP_CONNECT, result.route)
        assertEquals(TargetProbeStatus.PROXY_CONNECT_ESTABLISHED, result.status)
        assertFalse(result.verifiedTls)
        assertNotNull(result.tcpElapsedMs)
        assertTrue(request.startsWith("CONNECT 198.51.100.7:443 HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: 198.51.100.7:443\r\n"))
        assertFalse(request.contains("example.org"))
    }

    @Test fun failedProxyConnectIsNotReportedSuccessfulOrRetriedDirectly() {
        val target = TargetDiscoveryPlanner.plan("198.51.100.7", 443, false).single()
        val (result, request) = testProxy("HTTP/1.1 502 Bad Gateway\r\n\r\n") { port ->
            ProxyCandidateProbe.inspect(target, 1000, port, TargetSocketLease())
        }
        assertEquals(TargetProbeStatus.PROXY_CONNECT_REJECTED, result.status)
        assertFalse(result.succeeded)
        assertNull(result.tcpElapsedMs)
        assertTrue(request.startsWith("CONNECT 198.51.100.7:443"))
    }

    @Test fun authenticationChallengeIsNotSuccessfulProxyProbe() {
        val target = TargetDiscoveryPlanner.plan("example.org", 443, false).single()
        val (result, _) = testProxy("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n") { port ->
            ProxyCandidateProbe.inspect(target, 1000, port, TargetSocketLease())
        }
        assertEquals(TargetProbeStatus.PROXY_AUTH_REQUIRED, result.status)
        assertFalse(result.succeeded)
    }

    @Test fun malformedConnectResponseCannotBecomeSuccess() {
        val target = TargetDiscoveryPlanner.plan("example.org", 443, false).single()
        val (result, _) = testProxy("NOT HTTP\r\n\r\n") { port ->
            ProxyCandidateProbe.inspect(target, 1000, port, TargetSocketLease())
        }
        assertEquals(TargetProbeStatus.PROXY_PROTOCOL_ERROR, result.status)
        assertFalse(result.succeeded)
    }

    @Test fun noLocalListenerNeverFallsBackToDirect() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val port = socket.localPort
            socket.close()
            val target = TargetDiscoveryPlanner.plan("198.51.100.9", 443, false).single()
            val result = ProxyCandidateProbe.inspect(target, 300, port, TargetSocketLease())
            assertEquals(TargetProbeRoute.LOCAL_HTTP_CONNECT, result.route)
            assertFalse(result.succeeded)
            assertTrue(result.status != TargetProbeStatus.TCP_OPEN)
        }
    }

    @Test fun stoppedLeaseFailsClosedWithoutTryingLocalProxy() {
        val lease = TargetSocketLease().also { it.stop() }
        val target = TargetDiscoveryPlanner.plan("example.org", 443, false).single()
        val result = ProxyCandidateProbe.inspect(target, 500, 10809, lease)
        assertEquals(TargetProbeStatus.CANCELLED, result.status)
    }

    @Test fun invalidProxyPortAndTimeoutAreRejectedBeforeNetwork() {
        val target = TargetDiscoveryPlanner.plan("example.org", 443, true).single()
        for (port in listOf(0, -1, 65536)) {
            assertThrows(IllegalArgumentException::class.java) {
                ProxyCandidateProbe.inspect(target, 500, port, TargetSocketLease())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyCandidateProbe.inspect(target, 100, 10809, TargetSocketLease())
        }
    }

    @Test fun aConnect200AloneIsNotAValidTlsProbe() {
        val target = TargetDiscoveryPlanner.plan("198.51.100.7", 443, true, "example.org").single()
        val (result, request) = testProxy("HTTP/1.1 200 OK\r\n\r\n") { port ->
            ProxyCandidateProbe.inspect(target, 400, port, TargetSocketLease())
        }
        assertEquals(TargetProbeRoute.LOCAL_HTTP_CONNECT, result.route)
        assertFalse(result.verifiedTls)
        assertTrue(result.status != TargetProbeStatus.TLS_VERIFIED)
        assertEquals("example.org", target.sni)
        assertTrue(request.startsWith("CONNECT 198.51.100.7:443"))
    }
}
