package com.v2ray.ang.util

import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Read-only app-network TLS handshake; never represents an Xray proxy test. */
internal data class TlsHandshakeObservation(
    val status: String,
    val tcpElapsedMs: Long?,
    val handshakeElapsedMs: Long?,
    val tlsVersion: String?,
    val alpn: String?,
    val cipherSuite: String?,
    val certificateSubject: String?,
    val certificateSha256: String?,
    val certificateExpiresAt: Long?,
)

internal object TlsHandshakeProbe {
    fun inspect(host: String, port: Int, timeoutMs: Int = 4000): TlsHandshakeObservation {
        require(port in 1..65535 && timeoutMs in 300..10_000)
        val domain = IDN.toASCII(host.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
        require(domain.isNotBlank() && domain.length <= 253 &&
            !domain.contains('/') && !domain.contains('@') && !domain.contains(':'))
        var connectTime: Long? = null
        try {
            Socket().use { plain ->
                // Host resolution happens before the socket-connect stopwatch.
                val endpoint = InetSocketAddress(domain, port)
                val connectStarted = System.nanoTime()
                plain.connect(endpoint, timeoutMs)
                connectTime = elapsed(connectStarted)
                plain.soTimeout = timeoutMs
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(plain, domain, port, true) as SSLSocket).use { tls ->
                    tls.soTimeout = timeoutMs
                    val parameters = tls.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    tls.sslParameters = parameters
                    val handshakeStarted = System.nanoTime()
                    tls.startHandshake()
                    val handshakeTime = elapsed(handshakeStarted)
                    val session = tls.session
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: throw SSLPeerUnverifiedException("PEER_CERT_MISSING")
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    return TlsHandshakeObservation("OK", connectTime, handshakeTime,
                        session.protocol.take(40), negotiatedAlpn(tls), session.cipherSuite.take(120),
                        leaf.subjectX500Principal.name.take(140), fingerprint, leaf.notAfter.time)
                }
            }
        } catch (error: Exception) {
            // Static failure codes only, never raw URL/host or exception strings.
            return TlsHandshakeObservation(HttpsEvidence.classify(error), connectTime, null,
                null, null, null, null, null, null)
        }
    }

    private fun negotiatedAlpn(tls: SSLSocket): String? = runCatching {
        // Android's getApplicationProtocol is API-dependent; absent is not a failure.
        val method = tls.javaClass.methods.firstOrNull {
            it.name == "getApplicationProtocol" && it.parameterCount == 0
        }
        method?.invoke(tls) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(64)

    private fun elapsed(start: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
}
