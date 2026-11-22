package com.v2ray.ang.util.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal data class DnsResolver(val address: InetAddress, val port: Int) {
    val label: String get() = if (address.hostAddress.contains(':')) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"
}

/** Only literal IP resolvers: prevents hidden recursive system resolver lookups. */
internal object DnsResolverInput {
    fun parse(text: String): DnsResolver {
        val input = text.trim()
        val host: String
        val port: Int
        if (input.startsWith("[")) {
            val end = input.indexOf(']')
            if (end <= 1) throw IllegalArgumentException("INVALID_RESOLVER")
            host = input.substring(1, end)
            val suffix = input.substring(end + 1)
            port = if (suffix.isEmpty()) 53 else if (suffix.startsWith(':')) suffix.substring(1).toIntOrNull() ?: 0 else 0
        } else if (input.count { it == ':' } == 1 && input.contains('.')) {
            host = input.substringBeforeLast(':')
            port = input.substringAfterLast(':').toIntOrNull() ?: 0
        } else {
            host = input
            port = 53
        }
        if (port !in 1..65535 || host.contains('%')) throw IllegalArgumentException("INVALID_RESOLVER")
        val address = if (host.contains(':')) {
            if (!host.matches(Regex("[0-9a-fA-F:.]+"))) throw IllegalArgumentException("INVALID_RESOLVER")
            InetAddress.getByName(host)
        } else {
            val parts = host.split('.')
            if (parts.size != 4) throw IllegalArgumentException("INVALID_RESOLVER")
            val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: throw IllegalArgumentException("INVALID_RESOLVER") }
            InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
        }
        if (address.isAnyLocalAddress || address.isMulticastAddress) throw IllegalArgumentException("INVALID_RESOLVER")
        return DnsResolver(address, port)
    }
}

internal enum class DnsRoute { APP_UDP_TCP, LOCAL_SOCKS5_TCP }
internal data class DnsAttempt(val transport: String, val elapsedMs: Long, val status: String)
internal data class DnsProbeResult(
    val resolver: String,
    val domain: String,
    val qtype: DnsType,
    val status: String,
    val elapsedMs: Long,
    val answer: DnsMessage?,
    val attempts: List<DnsAttempt>,
    val route: DnsRoute = DnsRoute.APP_UDP_TCP,
) {
    val succeeded: Boolean get() = status == "OK" && answer?.rcode == 0
}

/** Read-only query over the app's current network route (possibly the active VPN). */
internal class DnsProbe(private val transactionId: () -> Int = { SecureRandom().nextInt(65536) }) {
    fun query(resolver: String, domain: String, type: DnsType, timeoutMs: Int = 1500,
        route: DnsRoute = DnsRoute.APP_UDP_TCP, localSocksPort: Int? = null): DnsProbeResult {
        require(timeoutMs in 300..10_000)
        val attempts = mutableListOf<DnsAttempt>()
        var addressLabel = resolver.trim().take(96)
        val start = System.nanoTime()
        val normalizedDomain = domain.trim().trimEnd('.')
        var decoded: DnsMessage? = null
        var status = "UNKNOWN"
        try {
            val endpoint = DnsResolverInput.parse(resolver)
            addressLabel = endpoint.label
            val id = transactionId()
            val request = DnsWire.query(normalizedDomain, type, id)
            val response = if (route == DnsRoute.LOCAL_SOCKS5_TCP) {
                val port = localSocksPort?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("INVALID_SOCKS_PORT")
                val tcpStart = System.nanoTime()
                try {
                    val tcp = DnsSocks5Tcp.exchange(endpoint.address, endpoint.port, port, request, timeoutMs)
                    attempts.add(DnsAttempt("SOCKS5/TCP", elapsed(tcpStart), "OK"))
                    tcp
                } catch (error: Exception) {
                    attempts.add(DnsAttempt("SOCKS5/TCP", elapsed(tcpStart), classify(error)))
                    throw error // No direct-network fallback after SOCKS failure.
                }
            } else {
                val udpStart = System.nanoTime()
                val udp = try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = timeoutMs
                        socket.connect(endpoint.address, endpoint.port)
                        socket.send(DatagramPacket(request, request.size))
                        val buffer = ByteArray(4096)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        buffer.copyOf(packet.length)
                    }
                } catch (error: Exception) {
                    attempts.add(DnsAttempt("UDP", elapsed(udpStart), classify(error)))
                    throw error
                }
                val truncated = DnsWire.isTruncated(udp, id)
                attempts.add(DnsAttempt("UDP", elapsed(udpStart), if (truncated) "TRUNCATED" else "OK"))
                if (truncated) {
                    val tcpStart = System.nanoTime()
                    try {
                        val tcp = Socket().use { socket ->
                            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), timeoutMs)
                            socket.soTimeout = timeoutMs
                            val out = DataOutputStream(socket.getOutputStream())
                            out.writeShort(request.size)
                            out.write(request)
                            out.flush()
                            val input = DataInputStream(socket.getInputStream())
                            val length = input.readUnsignedShort()
                            if (length < 12) throw DnsWireException("TRUNCATED_PACKET")
                            ByteArray(length).also { input.readFully(it) }
                        }
                        attempts.add(DnsAttempt("TCP", elapsed(tcpStart), "OK"))
                        tcp
                    } catch (error: Exception) {
                        attempts.add(DnsAttempt("TCP", elapsed(tcpStart), classify(error)))
                        throw error
                    }
                } else udp
            }
            decoded = DnsWire.decode(response, id)
            if (!decoded.questionName.equals(java.net.IDN.toASCII(normalizedDomain, java.net.IDN.USE_STD3_ASCII_RULES), ignoreCase = true) || decoded.questionType != type.code) {
                throw DnsWireException("QUESTION_MISMATCH")
            }
            // A parseable answer with TC=1 is still incomplete, including over TCP.
            status = if (decoded.truncated) "TRUNCATED_RESPONSE" else when (decoded.rcode) {
                0 -> if (decoded.records.isEmpty()) "NO_ANSWER" else "OK"
                3 -> "NXDOMAIN"
                2 -> "SERVFAIL"
                5 -> "REFUSED"
                1 -> "FORMERR"
                else -> "RCODE_${decoded.rcode}"
            }
        } catch (error: Exception) {
            status = classify(error)
            decoded = null
        }
        return DnsProbeResult(addressLabel, normalizedDomain.take(253), type, status,
            elapsed(start), decoded, attempts.toList(), route)
    }

    private fun elapsed(start: Long): Long = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
    private fun classify(error: Exception): String = when (error) {
        is DnsWireException -> error.code
        is DnsSocks5Exception -> error.code
        is IllegalArgumentException -> "INVALID_INPUT"
        is SocketTimeoutException -> "TIMEOUT"
        is PortUnreachableException, is ConnectException -> "UNREACHABLE"
        is NoRouteToHostException -> "NO_ROUTE"
        is IOException -> "NETWORK_ERROR"
        else -> "UNKNOWN"
    }
}
