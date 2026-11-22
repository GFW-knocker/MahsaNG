package com.v2ray.ang.ui

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.R
import com.v2ray.ang.util.HttpsEvidence
import com.v2ray.ang.util.HttpsEvidenceRoute
import com.v2ray.ang.util.HttpsObservation
import com.v2ray.ang.util.TlsHandshakeProbe
import com.v2ray.ang.util.TlsHandshakeObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Explicit single-request TLS/HTTPS evidence; no scan plan, health advice or saved-profile writes. */
class HttpsEvidenceActivity : BaseActivity() {
    private lateinit var host: EditText
    private lateinit var tlsPort: EditText
    private lateinit var proxyPort: EditText
    private lateinit var timeout: EditText
    private lateinit var direct: Button
    private lateinit var viaProxy: Button
    private lateinit var tlsOnly: Button
    private lateinit var stop: Button
    private lateinit var output: TextView
    private var generation = 0L
    private var job: Job? = null
    private var activeRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.https_evidence_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val scale = resources.displayMetrics.density
        fun dp(value: Int) = (scale * value + 0.5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(24))
        }
        fun label(textRes: Int) {
            column.addView(TextView(this).apply {
                setText(textRes)
                textSize = 16f
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(0, dp(10), 0, dp(4))
            })
        }
        fun field(labelRes: Int, defaultText: String, fieldId: Int, number: Boolean = false): EditText {
            label(labelRes)
            return EditText(this).also {
                it.id = fieldId // Stable view identity for Android hierarchy restoration.
                it.setText(defaultText)
                it.setSingleLine(true)
                if (number) it.inputType = InputType.TYPE_CLASS_NUMBER
                it.contentDescription = getString(labelRes)
                column.addView(it)
            }
        }
        label(R.string.https_evidence_route_warning)
        host = field(R.string.https_evidence_host,
            savedInstanceState?.getString("https_host") ?: "example.com",
            R.id.https_evidence_host_field)
        tlsPort = field(R.string.https_evidence_tls_port,
            savedInstanceState?.getString("https_tls_port") ?: "443",
            R.id.https_evidence_tls_port_field, true)
        proxyPort = field(R.string.https_evidence_proxy_port,
            savedInstanceState?.getString("https_proxy_port")
                ?: (configuredHttpPortOrNull()?.toString() ?: ""),
            R.id.https_evidence_proxy_port_field, true)
        timeout = field(R.string.https_evidence_timeout,
            savedInstanceState?.getString("https_timeout") ?: "4000",
            R.id.https_evidence_timeout_field, true)
        direct = Button(this).apply {
            setText(R.string.https_evidence_direct)
            setOnClickListener { runEvidence(viaLocalProxy = false) }
        }
        viaProxy = Button(this).apply {
            setText(R.string.https_evidence_via_proxy)
            setOnClickListener { runEvidence(viaLocalProxy = true) }
        }
        tlsOnly = Button(this).apply {
            setText(R.string.https_evidence_tls_only)
            setOnClickListener { runHandshake() }
        }
        stop = Button(this).apply {
            setText(R.string.https_evidence_stop)
            visibility = View.GONE
            setOnClickListener { stopRun() }
        }
        output = TextView(this).apply {
            setText(R.string.https_evidence_ready)
            textSize = 15f
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, dp(14), 0, dp(24))
        }
        listOf(direct, viaProxy, tlsOnly, stop, output).forEach(column::addView)
        setContentView(ScrollView(this).apply { addView(column) })
        if (savedInstanceState?.getBoolean("https_active_run") == true) {
            // Network work from the former Activity was interrupted. Never auto-resend.
            output.setText(R.string.https_evidence_stopped)
        } else {
            savedInstanceState?.getString("https_rendered_output")?.let { output.text = it }
        }
    }

    private fun runEvidence(viaLocalProxy: Boolean) {
        if (job?.isActive == true) return
        val deadline = timeout.text.toString().toIntOrNull()
        val port = (if (viaLocalProxy) proxyPort else tlsPort).text.toString().toIntOrNull()
        if (deadline == null || deadline !in 300..10_000 || port == null || port !in 1..65535) {
            output.setText(R.string.https_evidence_invalid)
            return
        }
        val endpoint = host.text.toString()
        val token = ++generation
        activeRun = true
        direct.isEnabled = false
        viaProxy.isEnabled = false
        tlsOnly.isEnabled = false
        stop.visibility = View.VISIBLE
        output.setText(R.string.https_evidence_running)
        job = lifecycleScope.launch {
            try {
                val observation = withContext(Dispatchers.IO) {
                    if (viaLocalProxy) HttpsEvidence.throughLocalHttpProxy(port, deadline)
                    else HttpsEvidence.directEndpoint(endpoint, port, deadline)
                }
                if (token == generation && isActive) render(observation)
            } catch (_: Exception) {
                if (token == generation) output.setText(R.string.https_evidence_invalid)
            } finally {
                if (token == generation) {
                    activeRun = false
                    direct.isEnabled = true
                    viaProxy.isEnabled = true
                    tlsOnly.isEnabled = true
                    stop.visibility = View.GONE
                }
            }
        }
    }

    private fun runHandshake() {
        if (job?.isActive == true) return
        // Endpoint identification during SSLSocket handshake is API 24+.
        // Earlier Android releases must not silently drop hostname verification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            output.setText(R.string.https_evidence_tls_unsupported)
            return
        }
        val deadline = timeout.text.toString().toIntOrNull()
        val port = tlsPort.text.toString().toIntOrNull()
        if (deadline == null || deadline !in 300..10_000 || port == null || port !in 1..65535) {
            output.setText(R.string.https_evidence_invalid)
            return
        }
        val endpoint = host.text.toString()
        val token = ++generation
        activeRun = true
        direct.isEnabled = false
        viaProxy.isEnabled = false
        tlsOnly.isEnabled = false
        stop.visibility = View.VISIBLE
        output.setText(R.string.https_evidence_running)
        job = lifecycleScope.launch {
            try {
                val evidence = withContext(Dispatchers.IO) {
                    TlsHandshakeProbe.inspect(endpoint, port, deadline)
                }
                if (token == generation && isActive) renderHandshake(evidence)
            } catch (_: Exception) {
                if (token == generation) output.setText(R.string.https_evidence_invalid)
            } finally {
                if (token == generation) {
                    activeRun = false
                    direct.isEnabled = true
                    viaProxy.isEnabled = true
                    tlsOnly.isEnabled = true
                    stop.visibility = View.GONE
                }
            }
        }
    }

    private fun renderHandshake(result: TlsHandshakeObservation) {
        fun clean(value: String?): String =
            value?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(140) ?: "—"
        fun line(key: Int, value: String) = getString(key) + ": " + value + "\n"
        output.text = buildString {
            append(line(R.string.https_evidence_route,
                getString(R.string.https_evidence_tls_only_route)))
            append(getString(R.string.https_evidence_tls_only_info)).append("\n")
            append(line(R.string.https_evidence_result, result.status))
            result.tcpElapsedMs?.let {
                append(line(R.string.https_evidence_tcp_connect, it.toString() + " ms"))
            }
            result.handshakeElapsedMs?.let {
                append(line(R.string.https_evidence_handshake, it.toString() + " ms"))
            }
            result.tlsVersion?.let {
                append(line(R.string.https_evidence_protocol, clean(it)))
            }
            result.alpn?.let {
                append(line(R.string.https_evidence_alpn, clean(it)))
            }
            result.cipherSuite?.let {
                append(line(R.string.https_evidence_cipher, clean(it)))
            }
            result.certificateSubject?.let {
                append(line(R.string.https_evidence_cert, clean(it)))
            }
            result.certificateSha256?.let {
                append(line(R.string.https_evidence_fingerprint, clean(it)))
            }
            result.certificateExpiresAt?.let {
                append(line(R.string.https_evidence_expiry,
                    DateFormat.getDateInstance().format(Date(it))))
            }
        }
    }

    private fun render(result: HttpsObservation) {
        fun clean(value: String?): String = value?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(140) ?: "—"
        fun line(key: Int, value: String) = "${getString(key)}: $value\n"
        output.text = buildString {
            append(line(R.string.https_evidence_route, getString(if (result.route == HttpsEvidenceRoute.LOCAL_HTTP_PROXY)
                R.string.https_evidence_route_proxy else R.string.https_evidence_route_app)))
            append(line(R.string.https_evidence_result, result.status))
            append(line(R.string.https_evidence_elapsed, "${result.elapsedMs} ms"))
            result.responseCode?.let { append(line(R.string.https_evidence_http_code, it.toString())) }
            result.cipherSuite?.let { append(line(R.string.https_evidence_cipher, clean(it))) }
            result.certificateSubject?.let { append(line(R.string.https_evidence_cert, clean(it))) }
            result.certificateSha256?.let { append(line(R.string.https_evidence_fingerprint, clean(it))) }
            result.certificateExpiresAt?.let {
                append(line(R.string.https_evidence_expiry, DateFormat.getDateInstance().format(Date(it))))
            }
            result.serverHeader?.let { append(line(R.string.https_evidence_server, clean(it))) }
            result.altSvcHeader?.let { append(line(R.string.https_evidence_alt_svc, clean(it))) }
            result.contentType?.let { append(line(R.string.https_evidence_type, clean(it))) }
            result.contentLength?.let { append(line(R.string.https_evidence_length, it.toString())) }
            if (result.route == HttpsEvidenceRoute.LOCAL_HTTP_PROXY) {
                append(line(R.string.https_evidence_connectivity, result.connectivity204.toString()))
            }
        }
    }

    private fun stopRun() {
        activeRun = false
        generation++
        job?.cancel()
        job = null
        direct.isEnabled = true
        viaProxy.isEnabled = true
        tlsOnly.isEnabled = true
        stop.visibility = View.GONE
        output.setText(R.string.https_evidence_stopped)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("https_host", host.text.toString())
        outState.putString("https_tls_port", tlsPort.text.toString())
        outState.putString("https_proxy_port", proxyPort.text.toString())
        outState.putString("https_timeout", timeout.text.toString())
        outState.putBoolean("https_active_run", activeRun || job?.isActive == true)
        outState.putString("https_rendered_output", output.text.toString().take(60_000))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        generation++
        job?.cancel()
        super.onDestroy()
    }
    private fun configuredHttpPortOrNull(): Int? {
        val configured = MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE)
            ?.decodeString(AppConfig.PREF_HTTP_PORT) ?: AppConfig.PORT_HTTP
        return configured.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
