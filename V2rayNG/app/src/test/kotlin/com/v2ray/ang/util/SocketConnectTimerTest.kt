package com.v2ray.ang.util

import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketConnectTimerTest {
    private class FakeSocket(private val fail: Boolean = false) : Socket() {
        var closes = 0
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (isClosed) throw SocketException("cancelled")
            if (fail) throw SocketTimeoutException("simulated timeout")
        }
        override fun close() {
            closes++
            super.close()
        }
    }

    @Test fun timedOutConnectUnregistersAndClosesSocket() {
        val socket = FakeSocket(fail = true)
        val active = mutableSetOf<Socket>()
        try {
            SocketConnectTimer.measure("example.invalid", 443, 10, socket, active::add, active::remove)
            throw AssertionError("expected timeout")
        } catch (_: SocketTimeoutException) {}
        assertTrue(active.isEmpty())
        assertEquals(1, socket.closes)
    }

    @Test fun successfulConnectIsPositiveAndDoesNotLeak() {
        val socket = FakeSocket()
        val active = mutableSetOf<Socket>()
        val delay = SocketConnectTimer.measure("example.invalid", 443, 10, socket, active::add, active::remove)
        assertTrue(delay >= 1L)
        assertTrue(active.isEmpty())
        assertEquals(1, socket.closes)
    }

    @Test fun failedRegistrationStillClosesSocket() {
        val socket = FakeSocket()
        try {
            SocketConnectTimer.measure("example.invalid", 443, 10, socket, { throw IllegalStateException("broken registry") }, {})
            throw AssertionError("expected register failure")
        } catch (_: IllegalStateException) {}
        assertEquals(1, socket.closes)
    }

    @Test fun cancellationDuringConnectUnregistersTheSocket() {
        val socket = FakeSocket()
        val active = mutableSetOf<Socket>()
        try {
            SocketConnectTimer.measure("example.invalid", 443, 10, socket, {
                active.add(it)
                it.close()
            }, active::remove)
            throw AssertionError("expected cancellation")
        } catch (_: SocketException) {}
        assertTrue(active.isEmpty())
        assertTrue(socket.closes >= 2)
    }
}
