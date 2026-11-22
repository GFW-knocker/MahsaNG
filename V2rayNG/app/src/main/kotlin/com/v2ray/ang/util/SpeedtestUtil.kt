package com.v2ray.ang.util

import android.content.Context
import com.tencent.mmkv.MMKV
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.responseLength
import kotlinx.coroutines.isActive
import libv2ray.Libv2ray
import java.io.IOException
import java.net.*
import java.util.*
import kotlin.coroutines.coroutineContext

object SpeedtestUtil {

    private val tcpTestingSockets = ArrayList<Socket>()
    private val tcpSettings by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    suspend fun tcping(url: String, port: Int): Long =
        tcpingEvidence(url, port).delayMs

    /** Same direct socket-connect preset as tcping; only adds transient safe evidence. */
    internal suspend fun tcpingEvidence(url: String, port: Int): TcpProbeEvidence {
        val preset = TcpProbePreset.fromStored(tcpSettings?.decodeString(AppConfig.PREF_TCP_PROBE_PRESET))
        val context = coroutineContext
        return TcpProbeEvidenceRunner.measure(preset, cancelled = { !context.isActive }) { timeoutMs ->
            try {
                val delay = SocketConnectTimer.measure(
                    host = url,
                    port = port,
                    timeoutMs = timeoutMs,
                    socket = Socket(),
                    onRegister = { socket ->
                        synchronized(this) { tcpTestingSockets.add(socket) }
                    },
                    onUnregister = { socket ->
                        synchronized(this) { tcpTestingSockets.remove(socket) }
                    },
                )
                delay to null
            } catch (error: Exception) {
                Log.d(AppConfig.ANG_PACKAGE, "tcping: " + SafeProbeFailure.safeReason(error))
                -1L to SafeProbeFailure.classify(error)
            }
        }
    }

    fun realPing(config: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, AppConfig.DelayTestUrl)
        } catch (e: Exception) {
            Log.d(AppConfig.ANG_PACKAGE, "realPing: " + SafeProbeFailure.safeReason(e))
            -1L
        }
    }

    fun ping(url: String): String {
        try {
            val command = "/system/bin/ping -c 3 $url"
            val process = Runtime.getRuntime().exec(command)
            val allText = process.inputStream.bufferedReader().use { it.readText() }
            if (!TextUtils.isEmpty(allText)) {
                val tempInfo = allText.substring(allText.indexOf("min/avg/max/mdev") + 19)
                val temps = tempInfo.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (temps.count() > 0 && temps[0].length < 10) {
                    return temps[0].toFloat().toInt().toString() + "ms"
                }
            }
        } catch (e: Exception) {
            Log.d(AppConfig.ANG_PACKAGE, "ping: " + SafeProbeFailure.safeReason(e))
        }
        return "-1ms"
    }

    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 3000): Long {
        try {
            return SocketConnectTimer.measure(
                host = url,
                port = port,
                timeoutMs = timeoutMs,
                socket = Socket(),
                onRegister = { socket ->
                    synchronized(this) { tcpTestingSockets.add(socket) }
                },
                onUnregister = { socket ->
                    synchronized(this) { tcpTestingSockets.remove(socket) }
                },
            )
        } catch (e: Exception) {
            Log.d(AppConfig.ANG_PACKAGE, "socketConnectTime: " + SafeProbeFailure.safeReason(e))
        }
        return -1L
    }

    fun closeAllTcpSockets() {
        // Never hold the registry monitor while closing a socket that may be in connect().
        val sockets = synchronized(this) {
            tcpTestingSockets.toList().also { tcpTestingSockets.clear() }
        }
        sockets.forEach { socket -> runCatching { socket.close() } }
    }

    fun testConnection(context: Context, port: Int): String {
        // return V2RayVpnService.measureV2rayDelay()
        var result: String
        var conn: HttpURLConnection? = null

        try {
            val url = URL("https",
                    "www.google.com",
                    "/generate_204")

            conn = url.openConnection(
                    Proxy(Proxy.Type.HTTP,
                            InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Connection", "close")
            conn.instanceFollowRedirects = false
            conn.useCaches = false

            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start

            if (code == 204 || code == 200 && conn.responseLength == 0L) {
                result = context.getString(R.string.connection_test_available, elapsed)
            } else {
                result = context.getString(R.string.connection_test_error_status_code, code)
            }
        } catch (e: Exception) {
            // Exception messages can contain full URLs and authentication tokens.
            Log.d(AppConfig.ANG_PACKAGE, "testConnection: " + SafeProbeFailure.safeReason(e))
            val error = when (SafeProbeFailure.classify(e)) {
                SafeProbeFailure.Kind.DNS -> R.string.probe_failure_dns
                SafeProbeFailure.Kind.REFUSED -> R.string.probe_failure_refused
                SafeProbeFailure.Kind.TIMEOUT -> R.string.probe_failure_timeout
                SafeProbeFailure.Kind.TLS_CERT -> R.string.probe_failure_tls
                SafeProbeFailure.Kind.NETWORK -> R.string.probe_failure_network
                SafeProbeFailure.Kind.OTHER -> R.string.probe_failure_other
            }
            result = context.getString(R.string.connection_test_error, context.getString(error))
        } finally {
            conn?.disconnect()
        }

        return result
    }

    fun getLibVersion(): String {
        return Libv2ray.checkVersionX()
    }

}
