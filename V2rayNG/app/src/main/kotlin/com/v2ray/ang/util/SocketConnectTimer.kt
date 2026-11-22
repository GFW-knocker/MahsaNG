package com.v2ray.ang.util

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Owns a TCP probe socket from registration through connect and unconditional cleanup. */
internal object SocketConnectTimer {
    fun measure(
        host: String,
        port: Int,
        timeoutMs: Int,
        socket: Socket,
        onRegister: (Socket) -> Unit,
        onUnregister: (Socket) -> Unit,
    ): Long {
        try {
            onRegister(socket)
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } finally {
            try {
                onUnregister(socket)
            } finally {
                socket.close()
            }
        }
    }
}
