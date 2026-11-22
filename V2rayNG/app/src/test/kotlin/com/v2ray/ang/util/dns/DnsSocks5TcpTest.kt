package com.v2ray.ang.util.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DnsSocks5TcpTest {
    @Test fun relaysDnsTcpThroughOnlyTheLoopbackSocksListener() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 5, loopback).use { listener ->
            listener.soTimeout = 2500
            val unexpected = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2500
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        output.write(byteArrayOf(5, 0)); output.flush()
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte()) // Numeric IPv4, never domain lookup
                        val destination = ByteArray(4).also { input.readFully(it) }
                        assertArrayEquals(byteArrayOf(203.toByte(), 0, 113, 8), destination)
                        assertEquals(53, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                        val framed = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                        assertArrayEquals(ByteArray(12) { it.toByte() }, framed)
                        output.writeShort(12)
                        output.write(ByteArray(12) { (it + 30).toByte() }); output.flush()
                    }
                } catch (error: Throwable) { unexpected.set(error) }
            }
            val result = DnsSocks5Tcp.exchange(
                InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 8)),
                53, listener.localPort, ByteArray(12) { it.toByte() }, 2000
            )
            worker.join(2500)
            assertFalse(worker.isAlive)
            unexpected.get()?.let { throw AssertionError("SOCKS listener failed", it) }
            assertArrayEquals(ByteArray(12) { (it + 30).toByte() }, result)
        }
    }

    @Test fun requiredProxyAuthenticationDoesNotFallBackToDirectDns() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 5, loopback).use { listener ->
            val worker = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val greeting = ByteArray(3).also { input.readFully(it) }
                    assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
                    socket.getOutputStream().write(byteArrayOf(5, 2))
                }
            }
            val failure = assertThrows(DnsSocks5Exception::class.java) {
                DnsSocks5Tcp.exchange(loopback, 53, listener.localPort, ByteArray(12), 1500)
            }
            assertEquals("SOCKS_AUTH_UNSUPPORTED", failure.code)
            worker.join(2500)
        }
    }

    @Test fun rejectsBadProxyPortWithoutConnecting() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsSocks5Tcp.exchange(InetAddress.getLoopbackAddress(), 53, 0, ByteArray(12), 1500)
        }
    }
}
