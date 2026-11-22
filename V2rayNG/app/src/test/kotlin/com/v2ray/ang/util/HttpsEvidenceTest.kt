package com.v2ray.ang.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class HttpsEvidenceTest {
    @Test fun classifierNeverLeaksExceptionMessageOrCause() {
        val secret = "https://token:secret@example.invalid/"
        val errors = listOf(
            SocketTimeoutException(secret) to "TIMEOUT",
            SSLHandshakeException(secret) to "TLS_HANDSHAKE_FAILED",
            SSLPeerUnverifiedException(secret) to "TLS_CERTIFICATE_UNVERIFIED",
            ConnectException(secret) to "CONNECTION_REFUSED",
            UnknownHostException(secret) to "DNS_LOOKUP_FAILED",
            IOException(secret) to "NETWORK_ERROR",
            RuntimeException(secret) to "UNKNOWN_ERROR",
        )
        errors.forEach { (error, expected) ->
            assertEquals(expected, HttpsEvidence.classify(error))
            assertFalse(HttpsEvidence.classify(error).contains("secret"))
        }
    }

    @Test fun connectivityRequiresExpected204OrEmpty200() {
        assertTrue(HttpsEvidence.isConnectivitySuccess(204, -1))
        assertTrue(HttpsEvidence.isConnectivitySuccess(200, 0))
        assertFalse(HttpsEvidence.isConnectivitySuccess(200, 123))
        assertFalse(HttpsEvidence.isConnectivitySuccess(302, 0))
        assertFalse(HttpsEvidence.isConnectivitySuccess(403, 0))
    }

    @Test fun invalidPortsAreRejectedBeforeNetworking() {
        assertThrows(IllegalArgumentException::class.java) { HttpsEvidence.throughLocalHttpProxy(0) }
        assertThrows(IllegalArgumentException::class.java) { HttpsEvidence.directEndpoint("example.com", 65536) }
        assertThrows(IllegalArgumentException::class.java) { HttpsEvidence.directEndpoint("example.com", 443, 100) }
    }
}
