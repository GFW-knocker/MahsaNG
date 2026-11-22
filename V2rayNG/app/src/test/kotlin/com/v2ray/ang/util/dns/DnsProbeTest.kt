package com.v2ray.ang.util.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class DnsProbeTest {
    private fun reply(request: ByteArray, truncated: Boolean = false): ByteArray {
        val question = request.copyOfRange(12, request.size - 11)
        val header = byteArrayOf(request[0], request[1],
            if (truncated) 0x83.toByte() else 0x81.toByte(), 0x80.toByte(), 0,1,0,
            if (truncated) 0 else 1, 0,0,0,0)
        return if (truncated) header + question else header + question +
            byteArrayOf(0xc0.toByte(),0x0c,0,1,0,1,0,0,0,60,0,4,203.toByte(),0,113,7)
    }

    @Test fun udpReturnsDnsAnswerAndElapsedTime() {
        val host = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, host).use { server ->
            server.soTimeout = 2000
            val worker = Thread {
                val bytes = ByteArray(2048)
                val packet = DatagramPacket(bytes, bytes.size)
                server.receive(packet)
                val reply = reply(bytes.copyOf(packet.length))
                server.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }.apply { isDaemon = true; start() }
            val result = DnsProbe { 0x1234 }.query("127.0.0.1:${server.localPort}",
                "www.example.com", DnsType.A, 1000)
            assertTrue(result.toString(), result.succeeded)
            assertEquals("203.0.113.7", result.answer?.records?.single()?.value)
            assertEquals(1, result.attempts.size)
            assertTrue(result.elapsedMs >= 1)
            worker.join(2000)
        }
    }

    @Test fun truncatedUdpRetriesOverTcpOnSameResolver() {
        val host = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, host).use { udp ->
            ServerSocket(udp.localPort, 2, host).use { tcp ->
                udp.soTimeout = 2000; tcp.soTimeout = 2000
                val udpWorker = Thread {
                    val bytes = ByteArray(2048)
                    val packet = DatagramPacket(bytes, bytes.size)
                    udp.receive(packet)
                    val reply = reply(bytes.copyOf(packet.length), true)
                    udp.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }.apply { isDaemon = true; start() }
                val tcpWorker = Thread {
                    tcp.accept().use { connection ->
                        val input = DataInputStream(connection.getInputStream())
                        val request = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                        val reply = reply(request)
                        DataOutputStream(connection.getOutputStream()).apply {
                            writeShort(reply.size); write(reply); flush()
                        }
                    }
                }.apply { isDaemon = true; start() }
                val result = DnsProbe { 0x1234 }.query("127.0.0.1:${udp.localPort}",
                    "www.example.com", DnsType.A, 1000)
                assertTrue(result.toString(), result.succeeded)
                assertEquals(listOf("UDP", "TCP"), result.attempts.map { it.transport })
                assertEquals(listOf("TRUNCATED", "OK"), result.attempts.map { it.status })
                udpWorker.join(2000); tcpWorker.join(2000)
            }
        }
    }

    @Test fun truncatedFinalTcpReplyCannotBecomeSuccessfulComparisonEvidence() {
        val host = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, host).use { udp ->
            ServerSocket(udp.localPort, 2, host).use { tcp ->
                udp.soTimeout = 2000
                tcp.soTimeout = 2000
                val udpWorker = Thread {
                    val bytes = ByteArray(2048)
                    val packet = DatagramPacket(bytes, bytes.size)
                    udp.receive(packet)
                    val answer = reply(bytes.copyOf(packet.length), truncated = true)
                    udp.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                }.apply { isDaemon = true; start() }
                val tcpWorker = Thread {
                    tcp.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val query = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                        val incomplete = reply(query).also {
                            it[2] = (it[2].toInt() or 0x02).toByte()
                        }
                        DataOutputStream(socket.getOutputStream()).apply {
                            writeShort(incomplete.size); write(incomplete); flush()
                        }
                    }
                }.apply { isDaemon = true; start() }
                val observed = DnsProbe { 0x1234 }.query("127.0.0.1:${udp.localPort}",
                    "www.example.com", DnsType.A, 1000)
                assertEquals("TRUNCATED_RESPONSE", observed.status)
                assertFalse(observed.succeeded)
                assertEquals(0, DnsScanAnalysis.summarize(listOf(observed)).validResponses)
                udpWorker.join(2000); tcpWorker.join(2000)
            }
        }
    }

    @Test fun resolverInputIsNumericAndCannotTriggerHiddenDnsLookup() {
        assertEquals(53, DnsResolverInput.parse("1.1.1.1").port)
        assertEquals(5353, DnsResolverInput.parse("[2001:db8::1]:5353").port)
        assertThrows(IllegalArgumentException::class.java) { DnsResolverInput.parse("dns.example.com") }
        assertThrows(IllegalArgumentException::class.java) { DnsResolverInput.parse("256.1.1.1") }
        assertThrows(IllegalArgumentException::class.java) { DnsResolverInput.parse("0.0.0.0") }
    }

    @Test fun unavailableResolverIsNotReportedAsSuccessful() {
        val result = DnsProbe { 0x1234 }.query("127.0.0.1:1", "example.com", DnsType.A, 300)
        assertFalse(result.succeeded)
        assertNull(result.answer)
        assertTrue(result.status in listOf("TIMEOUT", "UNREACHABLE", "NETWORK_ERROR", "NO_ROUTE"))
    }
}
