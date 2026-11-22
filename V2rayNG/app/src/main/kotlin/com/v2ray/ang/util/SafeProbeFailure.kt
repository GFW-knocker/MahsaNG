package com.v2ray.ang.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** No Throwable.toString()/message: URLs, hosts and tokens may appear in exception text. */
internal object SafeProbeFailure {
    enum class Kind { DNS, REFUSED, TIMEOUT, TLS_CERT, NETWORK, OTHER }

    fun classify(error: Throwable): Kind = when (error) {
        is UnknownHostException -> Kind.DNS
        is ConnectException -> Kind.REFUSED
        is SocketTimeoutException -> Kind.TIMEOUT
        is SSLHandshakeException -> Kind.TLS_CERT
        is IOException -> Kind.NETWORK
        else -> Kind.OTHER
    }

    fun safeReason(error: Throwable): String = when (classify(error)) {
        Kind.DNS -> "DNS lookup failed"
        Kind.REFUSED -> "Connection refused"
        Kind.TIMEOUT -> "Connection timed out"
        Kind.TLS_CERT -> "TLS handshake failed"
        Kind.NETWORK -> "Network request failed"
        Kind.OTHER -> "Unexpected error"
    }
}
