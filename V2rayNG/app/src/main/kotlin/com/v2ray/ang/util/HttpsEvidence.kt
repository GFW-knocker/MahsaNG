package com.v2ray.ang.util

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class HttpsEvidenceRoute { APP_NETWORK_NO_LOCAL_PROXY, LOCAL_HTTP_PROXY }

internal data class HttpsObservation(
    val route: HttpsEvidenceRoute,
    val status: String,
    val responseCode: Int?,
    val elapsedMs: Long,
    val cipherSuite: String?,
    val certificateSubject: String?,
    val certificateSha256: String?,
    val certificateExpiresAt: Long?,
    val serverHeader: String?,
    val altSvcHeader: String?,
    val contentType: String?,
    val contentLength: Int?,
    val connectivity204: Boolean,
)

/** Safe, read-only HTTPS evidence; default platform hostname/certificate verification stays enabled. */
internal object HttpsEvidence {
    private const val CONNECTIVITY_URL = "https://www.gstatic.com/generate_204"

    fun directEndpoint(host: String, port: Int, timeoutMs: Int = 4000): HttpsObservation {
        require(port in 1..65535 && timeoutMs in 300..10_000)
        val domain = IDN.toASCII(host.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
        require(domain.isNotEmpty() && domain.length <= 253 && !domain.contains('/') && !domain.contains('@') && !domain.contains(':'))
        return request(URL("https", domain, port, "/"), Proxy.NO_PROXY,
            HttpsEvidenceRoute.APP_NETWORK_NO_LOCAL_PROXY, timeoutMs)
    }

    fun throughLocalHttpProxy(port: Int, timeoutMs: Int = 4000): HttpsObservation {
        require(port in 1..65535 && timeoutMs in 300..10_000)
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        return request(URL(CONNECTIVITY_URL), proxy, HttpsEvidenceRoute.LOCAL_HTTP_PROXY, timeoutMs)
    }

    private fun request(url: URL, proxy: Proxy, route: HttpsEvidenceRoute, timeoutMs: Int): HttpsObservation {
        var connection: HttpsURLConnection? = null
        val started = System.nanoTime()
        try {
            connection = url.openConnection(proxy) as HttpsURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.requestMethod = "GET"
            val statusCode = connection.responseCode
            // Always use platform default trust and hostname verification.
            val certificate = runCatching { connection.serverCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            val cipher = runCatching { connection.cipherSuite }.getOrNull()?.take(120)
            val fingerprint = certificate?.encoded?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            return HttpsObservation(route, "HTTP_$statusCode", statusCode, elapsed(started), cipher,
                certificate?.subjectX500Principal?.name?.take(140), fingerprint,
                certificate?.notAfter?.time, connection.getHeaderField("Server")?.take(120),
                connection.getHeaderField("Alt-Svc")?.take(120),
                connection.contentType?.take(120), connection.contentLength,
                route == HttpsEvidenceRoute.LOCAL_HTTP_PROXY && isConnectivitySuccess(statusCode, connection.contentLengthLongCompat()))
        } catch (error: Exception) {
            return HttpsObservation(route, classify(error), null, elapsed(started), null, null,
                null, null, null, null, null, null, false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun HttpsURLConnection.contentLengthLongCompat(): Long = contentLength.toLong()

    fun isConnectivitySuccess(code: Int, length: Long): Boolean =
        code == 204 || (code == 200 && length == 0L)

    /** Do not expose exception messages or stack traces: they can contain credentials. */
    fun classify(error: Throwable): String = when (error) {
        is SSLPeerUnverifiedException -> "TLS_CERTIFICATE_UNVERIFIED"
        is SSLHandshakeException -> "TLS_HANDSHAKE_FAILED"
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS_LOOKUP_FAILED"
        is ConnectException -> "CONNECTION_REFUSED"
        is NoRouteToHostException -> "NO_ROUTE"
        is IOException -> "NETWORK_ERROR"
        else -> "UNKNOWN_ERROR"
    }

    private fun elapsed(start: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
}
