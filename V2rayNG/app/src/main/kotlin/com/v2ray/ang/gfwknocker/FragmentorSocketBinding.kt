package com.v2ray.ang.gfwknocker

import java.net.InetAddress
import java.net.ServerSocket

/** Explicitly honor each listener's configured interface (normally loopback). */
internal object FragmentorSocketBinding {
    fun bind(listenIp: String, port: Int): ServerSocket =
        ServerSocket(port, 128, InetAddress.getByName(listenIp))

    fun closeQuietly(socket: ServerSocket?) { runCatching { socket?.close() } }
}
