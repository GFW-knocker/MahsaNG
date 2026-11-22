package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SafeProbeFailureTest {
    @Test fun rejectsUntrustedExceptionMessagesForAllKnownFailures() {
        val leak = "password=my-secret@example.invalid"
        val failures = listOf(
            UnknownHostException(leak) to SafeProbeFailure.Kind.DNS,
            ConnectException(leak) to SafeProbeFailure.Kind.REFUSED,
            SocketTimeoutException(leak) to SafeProbeFailure.Kind.TIMEOUT,
            SSLHandshakeException(leak) to SafeProbeFailure.Kind.TLS_CERT,
            IOException(leak) to SafeProbeFailure.Kind.NETWORK,
            IllegalStateException(leak) to SafeProbeFailure.Kind.OTHER,
        )
        failures.forEach { (error, kind) ->
            assertEquals(kind, SafeProbeFailure.classify(error))
            val reason = SafeProbeFailure.safeReason(error)
            assertFalse(reason.contains("my-secret"))
            assertFalse(reason.contains("example.invalid"))
        }
    }

    @Test fun nestedExceptionsNeverExposeCredentialBearingCause() {
        val error = IOException("redacted", UnknownHostException("access_token=abc123"))
        assertEquals(SafeProbeFailure.Kind.NETWORK, SafeProbeFailure.classify(error))
        assertEquals("Network request failed", SafeProbeFailure.safeReason(error))
    }
}
