package com.v2ray.ang.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Files

class AppFetchFileDownloaderTest {
    private val dir = Files.createTempDirectory("mahsa-fetch-test").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Response(
        private val code: Int,
        private val payload: ByteArray,
        private val location: String? = null,
    ) : HttpURLConnection(URL("https://example.test/asset")) {
        var disconnected = false
        override fun getResponseCode(): Int = code
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getInputStream(): InputStream = ByteArrayInputStream(payload)
    }

    @Test fun failedStatusPreservesExistingAssetAndRemovesStagingFile() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val connection = Response(404, "not found".toByteArray())
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            openConnection = { _, _ -> connection },
        )
        assertFalse(saved)
        assertEquals("old", target.readText())
        assertTrue(connection.disconnected)
        assertEquals(listOf("geoip.dat"), dir.listFiles().orEmpty().map { it.name })
    }

    @Test fun successfulProxyDownloadReplacesTarget() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val connection = Response(200, "new".toByteArray())
        var chosenProxy: Proxy? = null
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            openConnection = { _, proxy -> chosenProxy = proxy; connection },
        )
        assertTrue(saved)
        assertEquals("new", target.readText())
        assertEquals(Proxy.Type.HTTP, chosenProxy?.type())
        assertTrue(connection.disconnected)
    }

    @Test fun zeroLengthResponseCannotOverwriteExistingTarget() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.Direct,
            openConnection = { _, _ -> Response(200, byteArrayOf()) },
        )
        assertFalse(saved)
        assertEquals("old", target.readText())
    }

    @Test fun failedProxyAttemptNeverOpensDirectConnection() {
        val target = dir.resolve("geoip.dat")
        var connections = 0
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            openConnection = { _, proxy ->
                connections += 1
                if (proxy.type() == Proxy.Type.DIRECT) error("Unexpected direct fallback")
                throw IOException("unreachable local proxy")
            },
        )
        assertFalse(saved)
        assertEquals(1, connections)
        assertFalse(target.exists())
    }

    @Test fun blockedRequestPerformsZeroNetworkOperations() {
        var contacted = false
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            dir.resolve("geoip.dat"),
            AppFetchRoutePolicy.Route.Blocked,
            openConnection = { _, _ ->
                contacted = true
                throw IOException("should not connect")
            },
        )
        assertFalse(saved)
        assertFalse(contacted)
    }

    @Test fun oversizedResponseIsRejectedWithoutOverwritingExistingAsset() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            maxBytes = 4L,
            openConnection = { _, _ -> Response(200, "0123456789".toByteArray()) },
        )
        assertFalse(saved)
        assertEquals("old", target.readText())
        assertEquals(listOf("geoip.dat"), dir.listFiles().orEmpty().map { it.name })
    }

    @Test fun httpsToHttpRedirectDowngradeIsRejected() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            openConnection = { _, _ -> Response(302, byteArrayOf(), location = "http://example.test/plain.dat") },
        )
        assertFalse(saved)
        assertEquals("old", target.readText())
    }

    @Test fun httpsRedirectStaysOnConfiguredProxyRoute() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val seenProxies = mutableListOf<Proxy.Type>()
        val saved = AppFetchFileDownloader.download(
            URL("https://example.test/geoip.dat"),
            target,
            AppFetchRoutePolicy.Route.ViaProxy(10809),
            openConnection = { url, proxy ->
                seenProxies.add(proxy.type())
                if (url.path == "/geoip.dat") {
                    Response(302, byteArrayOf(), location = "https://cdn.example.test/final.dat")
                } else {
                    Response(200, "redirected".toByteArray())
                }
            },
        )
        assertTrue(saved)
        assertEquals("redirected", target.readText())
        assertEquals(listOf(Proxy.Type.HTTP, Proxy.Type.HTTP), seenProxies)
    }

    @Test fun failedReplacementRestoresOriginalAssetFromBackup() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val staged = dir.resolve("geoip.dat.staging").apply { writeText("new") }
        var stagedRenameAttempts = 0
        val published = AppFetchFileDownloader.publishReplacement(
            temporary = staged,
            target = target,
            parent = dir,
            rename = { src, dst ->
                if (src == staged && dst == target) {
                    stagedRenameAttempts += 1
                    false
                } else {
                    src.renameTo(dst)
                }
            },
        )
        assertFalse(published)
        assertEquals(2, stagedRenameAttempts)
        assertTrue(target.exists())
        assertEquals("old", target.readText())
        assertTrue(staged.exists())
        // No orphaned backup files should remain after restoration.
        assertEquals(listOf("geoip.dat", "geoip.dat.staging"), dir.listFiles().orEmpty().map { it.name }.sorted())
    }

    @Test fun failedBackupRenamePreservesExistingAsset() {
        val target = dir.resolve("geoip.dat").apply { writeText("old") }
        val staged = dir.resolve("geoip.dat.staging").apply { writeText("new") }
        val published = AppFetchFileDownloader.publishReplacement(
            temporary = staged,
            target = target,
            parent = dir,
            rename = { _, _ -> false },
        )
        assertFalse(published)
        assertTrue(target.exists())
        assertEquals("old", target.readText())
        assertEquals(listOf("geoip.dat", "geoip.dat.staging"), dir.listFiles().orEmpty().map { it.name }.sorted())
    }
}
