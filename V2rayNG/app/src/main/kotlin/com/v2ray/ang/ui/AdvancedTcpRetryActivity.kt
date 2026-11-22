package com.v2ray.ang.ui

import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.util.AdvancedRetrySettings
import com.v2ray.ang.util.RetryPassMode
import com.v2ray.ang.viewmodel.AdvancedRetryScreenPhase
import com.v2ray.ang.viewmodel.AdvancedRetryScreenState
import com.v2ray.ang.viewmodel.AdvancedTcpRetryViewModel

/** Optional direct TCP diagnostic. No writes to saved profile delays or deletion. */
class AdvancedTcpRetryActivity : BaseActivity() {
    private val model: AdvancedTcpRetryViewModel by viewModels()
    private lateinit var attempts: EditText
    private lateinit var passes: EditText
    private lateinit var concurrency: EditText
    private lateinit var timeout: EditText
    private lateinit var modes: Spinner
    private lateinit var status: TextView
    private lateinit var prepareButton: Button
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.advanced_retry_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        fun description(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 16f
                setPadding(0, dp(4), 0, dp(12))
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            })
        }
        fun numeric(labelRes: Int, default: String): EditText {
            description(getString(labelRes))
            return EditText(this).also { field ->
                field.inputType = InputType.TYPE_CLASS_NUMBER
                field.setSingleLine(true)
                field.setText(default)
                field.contentDescription = getString(labelRes)
                content.addView(field)
            }
        }
        description(getString(R.string.advanced_retry_explanation))
        attempts = numeric(R.string.advanced_retry_attempts, "2")
        passes = numeric(R.string.advanced_retry_passes, "3")
        concurrency = numeric(R.string.advanced_retry_concurrency, "8")
        timeout = numeric(R.string.advanced_retry_timeout, "3000")
        description(getString(R.string.advanced_retry_mode))
        modes = Spinner(this).apply {
            adapter = ArrayAdapter(this@AdvancedTcpRetryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.advanced_retry_all), getString(R.string.advanced_retry_unresolved)))
        }
        content.addView(modes)
        prepareButton = Button(this).apply {
            setText(R.string.advanced_retry_prepare)
            setOnClickListener { prepare() }
        }
        content.addView(prepareButton)
        startButton = Button(this).apply {
            setText(R.string.advanced_retry_start)
            setOnClickListener { showBudgetConfirmation() }
        }
        content.addView(startButton)
        pauseButton = Button(this).apply { setOnClickListener { model.pauseOrContinue() } }
        content.addView(pauseButton)
        stopButton = Button(this).apply {
            setText(R.string.advanced_retry_stop)
            setOnClickListener { model.stop() }
        }
        content.addView(stopButton)
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(24))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextIsSelectable(true)
        }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
        model.state.observe(this) { render(it) }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { model.invalidatePrepared() }
        }
        listOf(attempts, passes, concurrency, timeout).forEach {
            it.addTextChangedListener(watcher)
        }
        modes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model.invalidatePrepared()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun prepare() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            AlertDialog.Builder(this)
                .setMessage(R.string.advanced_retry_error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val n = attempts.text.toString().toIntOrNull()
        val m = passes.text.toString().toIntOrNull()
        val c = concurrency.text.toString().toIntOrNull()
        val t = timeout.text.toString().toIntOrNull()
        if (n == null || m == null || c == null || t == null || t !in 500..10_000) {
            showInvalidInput()
            return
        }
        val policy = runCatching { AdvancedRetrySettings(n, m, c) }.getOrNull()
        if (policy == null) {
            showInvalidInput()
            return
        }
        model.prepare(
            intent.getStringExtra(EXTRA_SUBSCRIPTION).orEmpty(),
            intent.getStringExtra(EXTRA_KEYWORD).orEmpty(),
            policy,
            if (modes.selectedItemPosition == 1) RetryPassMode.UNRESOLVED_ONLY
            else RetryPassMode.ALL_EVERY_PASS,
            t,
        )
    }

    private fun showInvalidInput() {
        AlertDialog.Builder(this)
            .setMessage(R.string.advanced_retry_invalid)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showBudgetConfirmation() {
        val ready = model.state.value ?: return
        if (ready.phase != AdvancedRetryScreenPhase.READY || ready.targetCount == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.advanced_retry_budget_title)
            .setMessage(getString(R.string.advanced_retry_budget, ready.maxAttempts, ready.targetCount))
            .setPositiveButton(R.string.advanced_retry_start) { _, _ -> model.startPrepared() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: AdvancedRetryScreenState) {
        val phase = state.phase
        prepareButton.isEnabled = phase in setOf(
            AdvancedRetryScreenPhase.IDLE, AdvancedRetryScreenPhase.READY,
            AdvancedRetryScreenPhase.STOPPED, AdvancedRetryScreenPhase.FINISHED,
            AdvancedRetryScreenPhase.ERROR,
        )
        startButton.visibility = if (phase == AdvancedRetryScreenPhase.READY && state.targetCount > 0)
            View.VISIBLE else View.GONE
        pauseButton.visibility = if (phase == AdvancedRetryScreenPhase.RUNNING ||
            phase == AdvancedRetryScreenPhase.PAUSED) View.VISIBLE else View.GONE
        pauseButton.setText(if (phase == AdvancedRetryScreenPhase.PAUSED)
            R.string.advanced_retry_continue else R.string.advanced_retry_pause)
        stopButton.visibility = if (phase in setOf(
            AdvancedRetryScreenPhase.PREPARING, AdvancedRetryScreenPhase.READY,
            AdvancedRetryScreenPhase.RUNNING, AdvancedRetryScreenPhase.PAUSED,
        )) View.VISIBLE else View.GONE
        val report = state.report
        status.text = when {
            phase == AdvancedRetryScreenPhase.IDLE -> getString(R.string.advanced_retry_ready)
            phase == AdvancedRetryScreenPhase.PREPARING -> getString(R.string.advanced_retry_preparing)
            phase == AdvancedRetryScreenPhase.ERROR -> getString(R.string.advanced_retry_error)
            phase == AdvancedRetryScreenPhase.READY ->
                getString(R.string.advanced_retry_budget, state.maxAttempts, state.targetCount)
            report != null -> buildString {
                val s = report.snapshot
                val phaseLabel = when (phase) {
                    AdvancedRetryScreenPhase.RUNNING -> R.string.advanced_retry_phase_running
                    AdvancedRetryScreenPhase.PAUSED -> R.string.advanced_retry_phase_paused
                    AdvancedRetryScreenPhase.STOPPED -> R.string.advanced_retry_phase_stopped
                    else -> R.string.advanced_retry_phase_finished
                }
                append(getString(R.string.advanced_retry_progress,
                    getString(phaseLabel), s.pass, report.totalPasses, s.totalTargets,
                    s.attemptsCompleted, s.succeededProfiles, s.failedAttempts,
                    s.unknownAttempts, s.inFlight))
                if (phase == AdvancedRetryScreenPhase.FINISHED) {
                    append("\n\n")
                    append(getString(R.string.advanced_retry_preview,
                        report.previewLastThreePasses, report.previewThreeOfFive,
                        report.previewLastThreeAttempts))
                }
            }
            else -> getString(R.string.advanced_retry_stopped)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) model.stop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_SUBSCRIPTION = "advanced_retry_subscription"
        const val EXTRA_KEYWORD = "advanced_retry_keyword"
    }
}
