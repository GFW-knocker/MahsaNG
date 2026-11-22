package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class TlsHandshakeProbeTest {
    @Test fun rejectsInvalidTargetInputsBeforeOpeningSockets() {
        assertThrows(IllegalArgumentException::class.java) {
            TlsHandshakeProbe.inspect("example.com", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlsHandshakeProbe.inspect("example.com", 443, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlsHandshakeProbe.inspect("https://example.com/path", 443)
        }
    }

    @Test fun noLocalListenerCannotBeReportedAsVerifiedTls() {
        val listener = java.net.ServerSocket(0)
        val port = listener.localPort
        listener.close()
        val observed = TlsHandshakeProbe.inspect("127.0.0.1", port, 500)
        assertNotEquals("OK", observed.status)
        assertNull(observed.handshakeElapsedMs)
        assertNull(observed.tlsVersion)
        assertNull(observed.certificateSha256)
    }
}
