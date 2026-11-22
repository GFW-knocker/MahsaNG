package com.v2ray.ang.util

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One short-lived scan's socket ownership. Cancelling closes blocked connects and TLS handshakes. */
internal class TargetSocketLease {
    private val sockets = LinkedHashSet<Socket>()
    private var active = true

    @Synchronized fun isActive(): Boolean = active

    @Synchronized fun register(socket: Socket): Boolean {
        if (!active) {
            runCatching { socket.close() }
            return false
        }
        sockets.add(socket)
        return true
    }

    @Synchronized fun release(socket: Socket) {
        sockets.remove(socket)
    }

    fun stop() {
        val closing = synchronized(this) {
            active = false
            val old = sockets.toList()
            sockets.clear()
            old
        }
        closing.forEach { runCatching { it.close() } }
    }
}

internal enum class TargetProbeRoute { APP_NETWORK, LOCAL_HTTP_CONNECT }

internal enum class TargetProbeStatus {
    TCP_OPEN, TLS_VERIFIED, PROXY_CONNECT_ESTABLISHED,
    PROXY_AUTH_REQUIRED, PROXY_CONNECT_REJECTED, PROXY_PROTOCOL_ERROR,
    PROXY_LISTENER_UNAVAILABLE, CANCELLED, TIMEOUT, REFUSED, DNS_FAILED,
    NO_ROUTE, TLS_CERTIFICATE_UNVERIFIED, TLS_HANDSHAKE_FAILED, NETWORK_ERROR, UNKNOWN_ERROR,
}

internal data class TargetDiscoveryResult(
    val target: TargetDiscoveryTarget,
    val status: TargetProbeStatus,
    val tcpElapsedMs: Long?,
    val tlsElapsedMs: Long?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val route: TargetProbeRoute = TargetProbeRoute.APP_NETWORK,
    val passIndex: Int = 1,
) {
    val succeeded: Boolean
        get() = status == TargetProbeStatus.TCP_OPEN ||
            status == TargetProbeStatus.TLS_VERIFIED ||
            status == TargetProbeStatus.PROXY_CONNECT_ESTABLISHED

    val verifiedTls: Boolean get() = status == TargetProbeStatus.TLS_VERIFIED
}

/**
 * Single app-network socket probe. It may traverse an existing device VPN; it
 * neither protects/binds sockets nor proves the Xray profile works.
 * TLS is checked only with platform trust and HTTPS hostname verification.
 */
internal object TargetDiscoveryProbe {
    fun inspect(
        target: TargetDiscoveryTarget,
        timeoutMs: Int,
        lease: TargetSocketLease,
        clock: () -> Long = System::currentTimeMillis,
    ): TargetDiscoveryResult {
        require(timeoutMs in 300..10_000)
        require(target.port in 1..65535)
        require(!target.verifyTls || !target.sni.isNullOrBlank())
        val started = clock()
        var tcpMs: Long? = null
        var tlsMs: Long? = null
        var status = TargetProbeStatus.CANCELLED
        val plain = Socket()
        if (!lease.register(plain)) {
            return TargetDiscoveryResult(target, status, null, null, started, clock())
        }
        try {
            plain.use { socket ->
                if (!lease.isActive()) return TargetDiscoveryResult(
                    target, status, tcpMs, tlsMs, started, clock()
                )
                val connectStarted = System.nanoTime()
                socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                tcpMs = elapsed(connectStarted)
                if (target.verifyTls) {
                    val serverName = requireNotNull(target.sni)
                    socket.soTimeout = timeoutMs
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(socket, serverName, target.port, true) as SSLSocket)
                        .use { tls ->
                            tls.soTimeout = timeoutMs
                            val parameters = tls.sslParameters
                            applySniIfAvailable(parameters, serverName)
                            parameters.endpointIdentificationAlgorithm = "HTTPS"
                            tls.sslParameters = parameters
                            val handshakeStarted = System.nanoTime()
                            tls.startHandshake()
                            tlsMs = elapsed(handshakeStarted)
                            // A completed handshake implies platform trust and hostname
                            // verification were both enabled, not proxy reachability.
                            status = TargetProbeStatus.TLS_VERIFIED
                        }
                } else {
                    status = TargetProbeStatus.TCP_OPEN
                }
            }
        } catch (error: LinkageError) {
            // SSLParameters.endpointIdentificationAlgorithm requires Android API 24+.
            // Fail closed rather than silently disabling hostname verification.
            status = TargetProbeStatus.TLS_HANDSHAKE_FAILED
        } catch (error: Exception) {
            status = if (!lease.isActive()) TargetProbeStatus.CANCELLED else classify(error)
        } finally {
            lease.release(plain)
            runCatching { plain.close() }
        }
        return TargetDiscoveryResult(target, status, tcpMs, tlsMs, started, clock())
    }

    /** Set explicit SNIHostName on API 24+ runtimes without breaking API 21-23 class loading. */
    private fun applySniIfAvailable(parameters: SSLParameters, serverName: String) {
        runCatching {
            val sniClass = Class.forName("javax.net.ssl.SNIHostName")
            val sni = sniClass.getConstructor(String::class.java).newInstance(serverName)
            val setServerNames = parameters.javaClass.getMethod("setServerNames", List::class.java)
            setServerNames.invoke(parameters, listOf(sni))
        }
    }

    private fun classify(error: Exception): TargetProbeStatus = when (error) {
        is SSLPeerUnverifiedException -> TargetProbeStatus.TLS_CERTIFICATE_UNVERIFIED
        is SSLHandshakeException, is SSLException -> TargetProbeStatus.TLS_HANDSHAKE_FAILED
        is SocketTimeoutException -> TargetProbeStatus.TIMEOUT
        is ConnectException -> TargetProbeStatus.REFUSED
        is UnknownHostException -> TargetProbeStatus.DNS_FAILED
        is NoRouteToHostException -> TargetProbeStatus.NO_ROUTE
        is IOException -> TargetProbeStatus.NETWORK_ERROR
        else -> TargetProbeStatus.UNKNOWN_ERROR
    }

    private fun elapsed(start: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
}
