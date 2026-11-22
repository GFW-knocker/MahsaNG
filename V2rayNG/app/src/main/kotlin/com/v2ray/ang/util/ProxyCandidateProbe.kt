package com.v2ray.ang.util

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Probe one candidate through the *configured local HTTP proxy only*.
 *
 * The socket connects exclusively to 127.0.0.1, asks the HTTP proxy to CONNECT
 * to target.host:port, and optionally performs platform-verified TLS for the
 * explicitly chosen SNI. Never falls back to a direct target connection.
 *
 * CONNECT 200 is weaker evidence than a verified TLS handshake. Neither proves
 * the whole Android VPN is leak-free or that other traffic is routed by Xray.
 */
internal object ProxyCandidateProbe {
    private const val MAX_CONNECT_HEADER_BYTES = 8192
    private val responseLine = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: [^\\r\\n]*)?$")

    fun inspect(
        target: TargetDiscoveryTarget,
        timeoutMs: Int,
        localHttpPort: Int,
        lease: TargetSocketLease,
        clock: () -> Long = System::currentTimeMillis,
    ): TargetDiscoveryResult {
        require(timeoutMs in 300..10_000)
        require(localHttpPort in 1..65535)
        require(target.port in 1..65535 && target.host.isNotBlank())
        require(target.host.length <= 253 && target.host.none { it == '\r' || it == '\n' ||
            it == '/' || it == '@' })
        require(!target.verifyTls || !target.sni.isNullOrBlank())

        val started = clock()
        var status = TargetProbeStatus.CANCELLED
        var connectMs: Long? = null
        var tlsMs: Long? = null

        val plain = Socket()
        if (!lease.register(plain)) {
            return TargetDiscoveryResult(target, status, null, null,
                started, clock(), TargetProbeRoute.LOCAL_HTTP_CONNECT)
        }
        try {
            plain.use { socket ->
                socket.soTimeout = timeoutMs
                val begin = System.nanoTime()
                socket.connect(InetSocketAddress("127.0.0.1", localHttpPort), timeoutMs)
                if (!lease.isActive()) {
                    status = TargetProbeStatus.CANCELLED
                } else {
                    val authority = if (target.host.contains(':')) {
                        "[${target.host}]:${target.port}"
                    } else "${target.host}:${target.port}"
                    // The explicit remote IP (not SNI) is the CONNECT authority.
                    val request = "CONNECT $authority HTTP/1.1\r\n" +
                        "Host: $authority\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val responseCode = readConnectCode(socket)
                    status = when (responseCode) {
                        200 -> TargetProbeStatus.PROXY_CONNECT_ESTABLISHED
                        407 -> TargetProbeStatus.PROXY_AUTH_REQUIRED
                        else -> TargetProbeStatus.PROXY_CONNECT_REJECTED
                    }
                    if (responseCode == 200) {
                        connectMs = elapsed(begin)
                        if (target.verifyTls && lease.isActive()) {
                            val serverName = requireNotNull(target.sni)
                            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                            (factory.createSocket(socket, serverName, target.port, true) as SSLSocket)
                                .use { tls ->
                                    tls.soTimeout = timeoutMs
                                    val parameters = tls.sslParameters
                                    // The layered socket's explicit DNS hostname supplies SNI.
                                    // endpointIdentificationAlgorithm requires Android API 24+;
                                    // fail closed via LinkageError catch on older runtimes.
                                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                                    tls.sslParameters = parameters
                                    val tlsStart = System.nanoTime()
                                    tls.startHandshake()
                                    tlsMs = elapsed(tlsStart)
                                    status = TargetProbeStatus.TLS_VERIFIED
                                }
                        }
                    }
                }
            }
        } catch (_: LinkageError) {
            // Never silently skip HTTPS hostname verification on API 21-23.
            status = TargetProbeStatus.TLS_HANDSHAKE_FAILED
        } catch (error: Exception) {
            status = if (!lease.isActive()) TargetProbeStatus.CANCELLED else when (error) {
                is InvalidProxyReply, is EOFException -> TargetProbeStatus.PROXY_PROTOCOL_ERROR
                is SSLPeerUnverifiedException -> TargetProbeStatus.TLS_CERTIFICATE_UNVERIFIED
                is SSLHandshakeException, is SSLException -> TargetProbeStatus.TLS_HANDSHAKE_FAILED
                is SocketTimeoutException -> TargetProbeStatus.TIMEOUT
                is ConnectException -> TargetProbeStatus.PROXY_LISTENER_UNAVAILABLE
                is NoRouteToHostException -> TargetProbeStatus.NO_ROUTE
                is IOException -> TargetProbeStatus.NETWORK_ERROR
                else -> TargetProbeStatus.UNKNOWN_ERROR
            }
        } finally {
            lease.release(plain)
            runCatching { plain.close() }
        }

        return TargetDiscoveryResult(target, status, connectMs, tlsMs,
            started, clock(), TargetProbeRoute.LOCAL_HTTP_CONNECT)
    }

    private class InvalidProxyReply : IOException()

    /**
     * Byte-at-a-time parsing does not consume any subsequent TLS bytes that may
     * arrive in the same packet. Bounded headers prevent untrusted proxy memory abuse.
     */
    private fun readConnectCode(socket: Socket): Int {
        val input = socket.getInputStream()
        val response = StringBuilder()
        var suffix = ""
        while (response.length < MAX_CONNECT_HEADER_BYTES) {
            val byte = input.read()
            if (byte == -1) throw EOFException()
            if (byte !in 0..127) throw InvalidProxyReply()
            val char = byte.toChar()
            response.append(char)
            suffix = (suffix + char).takeLast(4)
            if (suffix == "\r\n\r\n") {
                val firstLine = response.toString().substringBefore("\r\n")
                return responseLine.matchEntire(firstLine)?.groupValues?.get(1)?.toIntOrNull()
                    ?: throw InvalidProxyReply()
            }
        }
        throw InvalidProxyReply()
    }

    private fun elapsed(start: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
}
