package com.v2ray.ang.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.util.BoundedProbeQueue
import com.v2ray.ang.util.CandidateProbeAttempt
import com.v2ray.ang.util.CandidateProbeRunPlan
import com.v2ray.ang.util.CandidateStability
import com.v2ray.ang.util.ProxyCandidateProbe
import com.v2ray.ang.util.TargetProbeRoute
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.RunNetworkObserver
import com.v2ray.ang.util.TargetDiscoveryPlanner
import com.v2ray.ang.util.TargetDiscoveryProbe
import com.v2ray.ang.util.TargetDiscoveryResult
import com.v2ray.ang.util.TargetDiscoveryTarget
import com.v2ray.ang.util.TargetProbeStatus
import com.v2ray.ang.util.TargetSocketLease
import com.v2ray.ang.viewmodel.TargetDiscoveryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Explicit target-first app-network diagnostic: does not alter VPN profiles or services.
 * A TCP open is NOT Xray-proxy success. TLS_OK means platform-verified TLS for the SNI.
 * No background scans: stopping, leaving the screen or rotating closes owned sockets.
 */
class TargetDiscoveryActivity : BaseActivity() {
    private val model: TargetDiscoveryViewModel by viewModels()
    private lateinit var targetsInput: EditText
    private lateinit var portInput: EditText
    private lateinit var sniInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var passesInput: EditText
    private lateinit var tlsCheckbox: CheckBox
    private lateinit var localProxyCheckbox: CheckBox
    private lateinit var successfulOnly: CheckBox
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var shareButton: Button
    private lateinit var status: TextView
    private lateinit var evidence: TextView
    private var job: Job? = null
    private var lease: TargetSocketLease? = null
    private var networkObserver: RunNetworkObserver? = null
    private var lastRenderMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.target_discovery_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        column.addView(TextView(this).apply {
            setText(R.string.target_discovery_route_warning)
            textSize = 14f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        fun field(titleRes: Int, initial: String, multiline: Boolean, numeric: Boolean = false): EditText {
            column.addView(TextView(this).apply {
                setText(titleRes)
                textSize = 15f
                setPadding(0, dp(12), 0, dp(2))
            })
            return EditText(this).also { input ->
                input.contentDescription = getString(titleRes)
                input.setSingleLine(!multiline)
                input.inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_TEXT
                }
                input.setText(initial)
                column.addView(input)
            }
        }

        targetsInput = field(R.string.target_discovery_targets,
            savedInstanceState?.getString("target_discovery_targets").orEmpty(), multiline = true)
        targetsInput.setHint(R.string.target_discovery_targets_hint)
        portInput = field(R.string.target_discovery_port,
            savedInstanceState?.getString("target_discovery_port") ?: "443", multiline = false, numeric = true)
        timeoutInput = field(R.string.target_discovery_timeout,
            savedInstanceState?.getString("target_discovery_timeout") ?: "1500", multiline = false, numeric = true)
        passesInput = field(R.string.target_discovery_passes,
            savedInstanceState?.getString("target_discovery_passes") ?: "1",
            multiline = false, numeric = true)
        localProxyCheckbox = CheckBox(this).apply {
            setText(R.string.target_discovery_use_local_proxy)
            isChecked = savedInstanceState?.getBoolean("target_discovery_proxy") ?: false
        }
        column.addView(localProxyCheckbox)
        tlsCheckbox = CheckBox(this).apply {
            setText(R.string.target_discovery_tls)
            isChecked = savedInstanceState?.getBoolean("target_discovery_tls") ?: false
        }
        column.addView(tlsCheckbox)
        sniInput = field(R.string.target_discovery_sni,
            savedInstanceState?.getString("target_discovery_sni").orEmpty(), multiline = false)
        startButton = Button(this).apply {
            setText(R.string.target_discovery_plan)
            setOnClickListener { prepare() }
        }
        stopButton = Button(this).apply {
            setText(R.string.target_discovery_stop)
            visibility = View.GONE
            setOnClickListener { stopRun(networkChanged = false) }
        }
        shareButton = Button(this).apply {
            setText(R.string.target_discovery_share)
            setOnClickListener { shareReport() }
        }
        successfulOnly = CheckBox(this).apply {
            setText(R.string.target_discovery_success_only)
            isChecked = model.verifiedOnly
            setOnCheckedChangeListener { _, checked ->
                model.verifiedOnly = checked
                render()
            }
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
            setTextIsSelectable(true)
        }
        evidence = TextView(this).apply {
            textSize = 14f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextIsSelectable(true)
        }
        listOf(startButton, stopButton, successfulOnly, shareButton, status, evidence)
            .forEach(column::addView)
        setContentView(ScrollView(this).apply { addView(column) })

        // A rotated Activity cannot inherit the previous Activity's sockets.
        // Keep completed evidence but do not silently resume network operations.
        if (model.running || savedInstanceState?.getBoolean("target_discovery_was_running") == true) {
            model.interrupt()
        }
        render()
    }

    private fun prepare() {
        if (model.running || job?.isActive == true) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            AlertDialog.Builder(this).setMessage(R.string.target_discovery_network_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val port = portInput.text.toString().toIntOrNull()
        val timeoutMs = timeoutInput.text.toString().toIntOrNull()
        val passes = passesInput.text.toString().toIntOrNull()
        if (port == null || timeoutMs == null || timeoutMs !in 300..10_000 ||
            passes == null || passes !in 1..CandidateProbeRunPlan.MAX_PASSES) {
            showInvalid()
            return
        }
        val planned = runCatching {
            TargetDiscoveryPlanner.plan(
                targetsInput.text.toString(), port, tlsCheckbox.isChecked,
                sniInput.text.toString(),
            )
        }.getOrNull()
        if (planned.isNullOrEmpty()) {
            showInvalid()
            return
        }
        val attempts = CandidateProbeRunPlan.create(planned, passes)
        val route = if (localProxyCheckbox.isChecked)
            TargetProbeRoute.LOCAL_HTTP_CONNECT else TargetProbeRoute.APP_NETWORK
        val proxyPort = if (route == TargetProbeRoute.LOCAL_HTTP_CONNECT)
            configuredHttpPortOrNull() else null
        if (route == TargetProbeRoute.LOCAL_HTTP_CONNECT && proxyPort == null) {
            AlertDialog.Builder(this).setMessage(R.string.target_discovery_proxy_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_discovery_confirm_title)
            .setMessage(getString(
                R.string.target_discovery_candidate_confirm,
                planned.size, passes, attempts.size, minOf(6, planned.size), timeoutMs,
                getString(if (tlsCheckbox.isChecked) R.string.target_discovery_tls_mode
                else R.string.target_discovery_tcp_mode),
                getString(if (route == TargetProbeRoute.LOCAL_HTTP_CONNECT)
                    R.string.target_discovery_proxy_route_label
                    else R.string.target_discovery_route_label),
            ))
            .setPositiveButton(R.string.target_discovery_run) { _, _ ->
                execute(attempts, timeoutMs, route, proxyPort, passes)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInvalid() {
        AlertDialog.Builder(this).setMessage(R.string.target_discovery_invalid)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun execute(
        attempts: List<CandidateProbeAttempt>,
        timeoutMs: Int,
        route: TargetProbeRoute,
        proxyPort: Int?,
        passes: Int,
    ) {
        if (model.running) return
        val observer = RunNetworkObserver.register(this)
        if (!observer.isCurrent()) {
            observer.close()
            AlertDialog.Builder(this).setMessage(R.string.target_discovery_network_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val owner = TargetSocketLease()
        lease = owner
        networkObserver = observer
        val token = ++model.generation
        model.running = true
        model.stopped = false
        model.networkChanged = false
        model.lastModeTls = attempts.first().target.verifyTls
        model.lastRoute = route
        model.requestedPasses = passes
        model.proxyUnavailable = false
        model.total = attempts.size
        model.lastStartMillis = System.currentTimeMillis()
        model.observations.clear()
        lastRenderMs = 0L
        render()

        job = lifecycleScope.launch {
            try {
                coroutineScope {
                    // One unique target is claimed per worker. Its passes happen
                    // sequentially, avoiding concurrent measurements of the same
                    // candidate masquerading as independent stability evidence.
                    val baseAttempts = attempts.filter { it.passIndex == 1 }
                    val queue = BoundedProbeQueue(baseAttempts, concurrency = 6)
                    repeat(queue.workerCount) {
                        launch(Dispatchers.IO) {
                            while (isActive && owner.isActive()) {
                                if (!observer.isCurrent()) {
                                    withContext(Dispatchers.Main) {
                                        if (token == model.generation) stopRun(networkChanged = true)
                                    }
                                    break
                                }
                                val attempt = queue.claim() ?: break
                                for (passIndex in 1..passes) {
                                    if (!isActive || !owner.isActive()) break
                                    if (!observer.isCurrent()) {
                                        withContext(Dispatchers.Main) {
                                            if (token == model.generation) stopRun(networkChanged = true)
                                        }
                                        break
                                    }
                                    val observation = when (route) {
                                        TargetProbeRoute.APP_NETWORK ->
                                            TargetDiscoveryProbe.inspect(attempt.target, timeoutMs, owner)
                                        TargetProbeRoute.LOCAL_HTTP_CONNECT ->
                                            ProxyCandidateProbe.inspect(attempt.target, timeoutMs,
                                                requireNotNull(proxyPort), owner)
                                    }.copy(passIndex = passIndex)
                                    withContext(Dispatchers.Main) {
                                        if (token == model.generation && model.running && owner.isActive()) {
                                            if (!observer.isCurrent()) {
                                                stopRun(networkChanged = true)
                                            } else if (observation.status == TargetProbeStatus.PROXY_LISTENER_UNAVAILABLE) {
                                                model.proxyUnavailable = true
                                                stopRun(networkChanged = false)
                                            } else if (observation.status != TargetProbeStatus.CANCELLED) {
                                                model.observations.add(observation)
                                                val now = SystemClock.elapsedRealtime()
                                                if (now - lastRenderMs >= 150 || model.observations.size == model.total) {
                                                    lastRenderMs = now
                                                    render()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Stop or network handover invalidates in-flight observations.
            } finally {
                owner.stop()
                observer.close()
                if (token == model.generation) {
                    model.running = false
                    lease = null
                    networkObserver = null
                    render()
                }
            }
        }
    }

    private fun stopRun(networkChanged: Boolean) {
        if (!model.running) return
        model.networkChanged = model.networkChanged || networkChanged
        model.interrupt()
        job?.cancel()
        job = null
        lease?.stop()
        networkObserver?.close()
        lease = null
        networkObserver = null
        render()
    }

    private fun render() {
        startButton.isEnabled = !model.running
        stopButton.visibility = if (model.running) View.VISIBLE else View.GONE
        shareButton.isEnabled = !model.running && model.observations.isNotEmpty()
        val rows = model.observations.toList()
        val ok = rows.count { it.succeeded }
        val summaries = CandidateStability.summarize(
            rows, model.requestedPasses, System.currentTimeMillis(),
        )
        status.text = buildString {
            append(getString(R.string.target_discovery_progress, rows.size, model.total, ok))
            append("\n")
            append(getString(if (model.lastRoute == TargetProbeRoute.LOCAL_HTTP_CONNECT)
                R.string.target_discovery_proxy_route_label
                else R.string.target_discovery_route_label))
            append("\n")
            append(getString(R.string.target_discovery_stable_summary,
                summaries.count { it.stableProxyTls }, summaries.size))
            if (model.networkChanged) append("\n")
                .append(getString(R.string.target_discovery_network_changed))
            else if (model.proxyUnavailable) append("\n")
                .append(getString(R.string.target_discovery_proxy_unavailable))
            else if (model.stopped) append("\n")
                .append(getString(R.string.target_discovery_stopped))
        }
        val shown = if (model.verifiedOnly) rows.filter { it.succeeded } else rows
        evidence.text = if (shown.isEmpty()) getString(R.string.target_discovery_no_results)
        else buildString {
            append(getString(R.string.target_discovery_candidate_summary_heading))
            append("\n")
            // The successful-only filter hides individual failed rows, never
            // rewrites the stability denominator or erases failed passes.
            for (summary in summaries) {
                append(getString(R.string.target_discovery_candidate_summary_row,
                    summary.target.host, summary.target.port,
                    summary.successes, summary.attempts, summary.expectedPasses,
                    summary.verifiedTls,
                    summary.medianMs?.toString() ?: "-",
                    if (summary.stableProxyTls)
                        getString(R.string.target_discovery_stable_yes)
                    else getString(R.string.target_discovery_stable_no)))
                append("\n")
            }
            append("\n").append(getString(R.string.target_discovery_observations_heading))
            append("\n")
            append(shown.joinToString("\n\n") { item -> rowText(item) })
        }
    }

    private fun rowText(item: TargetDiscoveryResult): String =
        getString(
            R.string.target_discovery_result,
            item.target.host, item.target.port, item.target.source.name,
            item.status.name,
            item.tcpElapsedMs?.toString() ?: "-",
            item.tlsElapsedMs?.toString() ?: "-",
            item.target.sni ?: getString(R.string.target_discovery_none),
        ) + "\n" + getString(
            R.string.target_discovery_candidate_provenance,
            item.passIndex, item.route.name,
        )

    private fun shareReport() {
        if (model.running || model.observations.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.target_discovery_share)
            .setMessage(R.string.target_discovery_share_warning)
            .setPositiveButton(R.string.target_discovery_share) { _, _ ->
                val report = buildString {
                    append("MahsaNG target-first candidate scan\n")
                    append("Route: ").append(model.lastRoute.name).append("\n")
                    append("Local HTTP CONNECT is a configured proxy listener, not device-wide VPN proof.\n")
                    append("Run started (epoch ms): ").append(model.lastStartMillis).append("\n")
                    append("Mode: ").append(if (model.lastModeTls) "TLS_HOSTNAME_VERIFIED" else "TCP_ONLY").append("\n")
                    append("Requested passes per target: ").append(model.requestedPasses).append("\n")
                    append("Completed: ").append(model.observations.size).append("/")
                        .append(model.total).append("\n")
                    append("Interrupted: ").append(model.stopped).append("\n")
                    append("Network changed: ").append(model.networkChanged).append("\n")
                    append("Proxy listener unavailable: ").append(model.proxyUnavailable).append("\n")
                    val summaries = CandidateStability.summarize(
                        model.observations, model.requestedPasses, System.currentTimeMillis()
                    )
                    append("Candidate stability (proxy TLS only):\n")
                    summaries.forEach { summary ->
                        append(summary.target.host).append(":").append(summary.target.port)
                            .append(" | route=").append(summary.route.name)
                            .append(" | success=").append(summary.successes)
                            .append("/").append(summary.attempts)
                            .append("/").append(summary.expectedPasses)
                            .append(" | tlsVerified=").append(summary.verifiedTls)
                            .append(" | medianMs=").append(summary.medianMs ?: "N/A")
                            .append(" | stableProxyTls=").append(summary.stableProxyTls)
                            .append("\n")
                    }
                    append("\n")
                    for (item in model.observations.take(CandidateProbeRunPlan.MAX_ATTEMPTS)) {
                        append(item.target.host).append(":").append(item.target.port)
                            .append(" | source=").append(item.target.source.name)
                            .append(" | sni=").append(item.target.sni ?: "NONE")
                            .append(" | status=").append(item.status.name)
                            .append(" | tcpMs=").append(item.tcpElapsedMs ?: "N/A")
                            .append(" | tlsMs=").append(item.tlsElapsedMs ?: "N/A")
                            .append(" | route=").append(item.route.name)
                            .append(" | pass=").append(item.passIndex)
                            .append(" | observedAt=").append(item.endedAtMillis)
                            .append("\n")
                    }
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.target_discovery_share)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("target_discovery_targets", targetsInput.text.toString())
        outState.putString("target_discovery_port", portInput.text.toString())
        outState.putString("target_discovery_timeout", timeoutInput.text.toString())
        outState.putString("target_discovery_passes", passesInput.text.toString())
        outState.putBoolean("target_discovery_proxy", localProxyCheckbox.isChecked)
        outState.putString("target_discovery_sni", sniInput.text.toString())
        outState.putBoolean("target_discovery_tls", tlsCheckbox.isChecked)
        outState.putBoolean("target_discovery_was_running", model.running)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (model.running) stopRun(networkChanged = false)
        super.onStop()
    }

    override fun onDestroy() {
        if (model.running) stopRun(networkChanged = false)
        super.onDestroy()
    }

    private fun configuredHttpPortOrNull(): Int? {
        val configured = MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE)
            ?.decodeString(AppConfig.PREF_HTTP_PORT) ?: AppConfig.PORT_HTTP
        return configured.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
