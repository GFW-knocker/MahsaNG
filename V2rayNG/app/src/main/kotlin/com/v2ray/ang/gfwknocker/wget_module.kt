package com.v2ray.ang.gfwknocker

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Fetch bounded HTTPS announcement text using Android's system networking.
 * A running VPN is applied by the OS; no certificate-bypass or private proxy
 * is introduced. Kept named for compatibility with the existing Java UI.
 */
object wget_module {
    private const val MAX_ANNOUNCEMENT_BYTES = 256 * 1024
    private const val TOTAL_FETCH_MS = 20_000L

    @JvmStatic
    fun auto_routed_fetch_remote_text(url: String): String = fetch(url).orEmpty()

    internal fun fetch(
        rawUrl: String,
        nowNanos: () -> Long = { System.nanoTime() },
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): String? {
        val startedNanos = nowNanos()
        fun remainingMillis(): Long =
            TOTAL_FETCH_MS - (nowNanos() - startedNanos) / 1_000_000L

        var uri = secureUri(rawUrl) ?: return null
        for (redirectCount in 0..3) {
            val remaining = remainingMillis()
            if (remaining <= 0) return null
            val connection = try { open(uri.toURL()) } catch (_: Exception) { return null }
            try {
                connection.connectTimeout = minOf(7_000L, remaining).toInt().coerceAtLeast(1)
                connection.readTimeout = minOf(10_000L, remaining).toInt().coerceAtLeast(1)
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val status = connection.responseCode
                if (remainingMillis() <= 0) return null
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirectCount == 3) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    uri = secureUri(uri.resolve(location).toString()) ?: return null
                    continue
                }
                if (status !in 200..299) return null
                // contentLengthLong was added after the app's Android 21 minimum.
                if (connection.contentLength > MAX_ANNOUNCEMENT_BYTES) return null
                val result = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val left = remainingMillis()
                        if (left <= 0) return null
                        // Recalculate the timeout as the overall budget shrinks.
                        connection.readTimeout = minOf(10_000L, left).toInt().coerceAtLeast(1)
                        val count = input.read(chunk)
                        if (remainingMillis() <= 0) return null
                        if (count < 0) break
                        if (result.size() + count > MAX_ANNOUNCEMENT_BYTES) return null
                        result.write(chunk, 0, count)
                    }
                }
                return result.toString(Charsets.UTF_8.name())
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun secureUri(raw: String): URI? = try {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
            uri.port > 65535 || uri.port == 0) null else uri
    } catch (_: Exception) { null }
}
