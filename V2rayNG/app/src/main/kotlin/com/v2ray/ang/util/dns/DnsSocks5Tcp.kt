package com.v2ray.ang.util.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Typed, static failures; never include proxy/endpoint content in errors. */
internal class DnsSocks5Exception(val code: String) : Exception(code)

/** DNS-over-TCP through a loopback SOCKS5 listener, not the Xray internal DNS engine. */
internal object DnsSocks5Tcp {
    fun exchange(
        resolverAddress: InetAddress,
        resolverPort: Int,
        socksPort: Int,
        request: ByteArray,
        timeoutMs: Int,
    ): ByteArray {
        require(socksPort in 1..65535 && resolverPort in 1..65535)
        require(timeoutMs in 300..10_000)
        require(request.size in 12..65535) // DNS header and TCP frame limits
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Explicit SOCKS5 no-auth negotiation. Do not silently fall back to SOCKS4.
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            if (input.readUnsignedByte() != 5) throw DnsSocks5Exception("SOCKS_PROTOCOL")
            if (input.readUnsignedByte() != 0) throw DnsSocks5Exception("SOCKS_AUTH_UNSUPPORTED")

            val address = resolverAddress.address
            val atyp = when (address.size) {
                4 -> 1
                16 -> 4
                else -> throw DnsSocks5Exception("SOCKS_INVALID_ADDRESS")
            }
            output.write(byteArrayOf(5, 1, 0, atyp.toByte()))
            output.write(address)
            output.writeShort(resolverPort)
            output.flush()

            if (input.readUnsignedByte() != 5) throw DnsSocks5Exception("SOCKS_PROTOCOL")
            val reply = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw DnsSocks5Exception("SOCKS_PROTOCOL")
            val replyAddressType = input.readUnsignedByte()
            if (reply != 0) throw DnsSocks5Exception("SOCKS_CONNECT_FAILED")
            val replyAddressSize = when (replyAddressType) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw DnsSocks5Exception("SOCKS_PROTOCOL")
            }
            input.readFully(ByteArray(replyAddressSize))
            input.readUnsignedShort() // BND.PORT

            output.writeShort(request.size)
            output.write(request)
            output.flush()
            val size = input.readUnsignedShort()
            if (size < 12) throw DnsSocks5Exception("TRUNCATED_PACKET")
            if (size > 32768) throw DnsSocks5Exception("RESPONSE_TOO_LARGE")
            return ByteArray(size).also(input::readFully)
        }
    }
}
