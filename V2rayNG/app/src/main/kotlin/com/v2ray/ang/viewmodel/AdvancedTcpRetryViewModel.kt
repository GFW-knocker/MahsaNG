package com.v2ray.ang.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.v2ray.ang.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class AdvancedRetryScreenPhase {
    IDLE, PREPARING, READY, RUNNING, PAUSED, STOPPED, FINISHED, ERROR
}

internal data class AdvancedRetryScreenState(
    val phase: AdvancedRetryScreenPhase,
    val targetCount: Int = 0,
    val maxAttempts: Long = 0L,
    val report: AdvancedTcpRetryReport? = null,
)

/** A process-local diagnostic. Never persists or automatically resumes after process death. */
internal class AdvancedTcpRetryViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(AdvancedRetryScreenState(AdvancedRetryScreenPhase.IDLE))

    private data class Prepared(
        val targets: List<AdvancedRetryTarget>,
        val settings: AdvancedRetrySettings,
        val mode: RetryPassMode,
        val timeoutMs: Int,
    )

    @Volatile private var currentRunId: String = ""
    private var prepared: Prepared? = null
    private var session: AdvancedTcpRetrySession? = null
    private var networkObserver: RunNetworkObserver? = null
    private var preparation: Job? = null
    private var lastAppliedReportSequence = 0L

    /** Snapshot the selected scope once on IO, never the mutable Activity cache. */
    fun prepare(subscriptionId: String, keywordFilter: String,
                settings: AdvancedRetrySettings, mode: RetryPassMode, timeoutMs: Int) {
        require(timeoutMs in 500..10_000)
        stopInternal(publishStop = false)
        val id = UUID.randomUUID().toString()
        currentRunId = id
        lastAppliedReportSequence = 0L
        state.value = AdvancedRetryScreenState(AdvancedRetryScreenPhase.PREPARING)
        preparation = viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                val guids = MmkvManager.decodeServerList().toList().distinct()
                val targets = guids.mapNotNull { guid ->
                    if (!isActive || currentRunId != id) return@mapNotNull null
                    val config = runCatching { MmkvManager.decodeServerConfig(guid) }.getOrNull()
                        ?: return@mapNotNull null
                    if (subscriptionId.isNotEmpty() && config.subscriptionId != subscriptionId) return@mapNotNull null
                    if (keywordFilter.isNotEmpty() && !config.remarks.contains(keywordFilter)) return@mapNotNull null
                    val outbound = config.getProxyOutbound() ?: return@mapNotNull null
                    if (!TcpProbeEligibility.supportsTcpConnect(
                            config.configType.name, outbound.protocol, outbound.streamSettings?.network
                        )) return@mapNotNull null
                    val address = outbound.getServerAddress()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val port = outbound.getServerPort()?.takeIf { it in 1..65535 }
                        ?: return@mapNotNull null
                    AdvancedRetryTarget(guid, ProfileRevisionDigest.fromSerializedConfig(gson.toJson(config)), address, port)
                }
                withContext(Dispatchers.Main) {
                    if (currentRunId != id || !isActive) return@withContext
                    prepared = Prepared(targets, settings, mode, timeoutMs)
                    state.value = AdvancedRetryScreenState(
                        phase = AdvancedRetryScreenPhase.READY,
                        targetCount = targets.size,
                        maxAttempts = targets.size.toLong() * settings.attemptsPerPass * settings.passes,
                    )
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (currentRunId == id) state.value = AdvancedRetryScreenState(AdvancedRetryScreenPhase.ERROR)
                }
            }
        }
    }

    /** Any changed budget input revokes an in-flight preview or READY. */
    fun invalidatePrepared() {
        if (state.value?.phase in setOf(AdvancedRetryScreenPhase.PREPARING,
                AdvancedRetryScreenPhase.READY)) {
            stopInternal(publishStop = false)
            state.value = AdvancedRetryScreenState(AdvancedRetryScreenPhase.IDLE)
        }
    }

    /** Must be called only after explicit human review of the maximum-attempt budget. */
    fun startPrepared(): Boolean {
        val ready = prepared ?: return false
        if (state.value?.phase != AdvancedRetryScreenPhase.READY || ready.targets.isEmpty()) return false
        prepared = null
        val id = currentRunId
        val app = getApplication<Application>()
        // An activeNetwork identity snapshot alone cannot detect A -> B -> A.
        // Register a per-session callback; fail closed if the network cannot be monitored.
        val observer = RunNetworkObserver.register(app)
        if (!observer.isCurrent()) {
            observer.close()
            state.value = AdvancedRetryScreenState(AdvancedRetryScreenPhase.ERROR)
            return false
        }
        networkObserver = observer
        val networkStillCurrent = { observer.isCurrent() }
        val lookupRevision = { guid: String ->
            runCatching {
                MmkvManager.decodeServerConfig(guid)?.let {
                    ProfileRevisionDigest.fromSerializedConfig(Gson().toJson(it))
                }
            }.getOrNull()
        }
        val runner = AdvancedTcpRetrySession(
            scope = viewModelScope, runId = id, targets = ready.targets,
            settings = ready.settings, mode = ready.mode, timeoutMs = ready.timeoutMs,
            currentRevision = lookupRevision, networkStillCurrent = networkStillCurrent,
            onReport = { report ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (currentRunId == id && report.sequence > lastAppliedReportSequence) {
                        lastAppliedReportSequence = report.sequence
                        if (report.snapshot.phase == RetryRunPhase.FINISHED ||
                            report.snapshot.phase == RetryRunPhase.STOPPED) {
                            if (networkObserver === observer) {
                                networkObserver = null
                                observer.close()
                            }
                        }
                        state.value = AdvancedRetryScreenState(
                            phase = when (report.snapshot.phase) {
                                RetryRunPhase.RUNNING -> AdvancedRetryScreenPhase.RUNNING
                                RetryRunPhase.PAUSED -> AdvancedRetryScreenPhase.PAUSED
                                RetryRunPhase.STOPPED -> AdvancedRetryScreenPhase.STOPPED
                                RetryRunPhase.FINISHED -> AdvancedRetryScreenPhase.FINISHED
                            },
                            targetCount = ready.targets.size,
                            maxAttempts = ready.targets.size.toLong() *
                                ready.settings.attemptsPerPass * ready.settings.passes,
                            report = report,
                        )
                    }
                }
            },
        )
        session = runner
        return runCatching {
            runner.start()
            true
        }.getOrElse {
            // Ignore queued callbacks from a run whose start failed.
            currentRunId = ""
            observer.close()
            if (networkObserver === observer) networkObserver = null
            session = null
            state.value = AdvancedRetryScreenState(AdvancedRetryScreenPhase.ERROR)
            false
        }
    }

    fun pauseOrContinue() {
        when (state.value?.phase) {
            AdvancedRetryScreenPhase.RUNNING -> session?.pause()
            AdvancedRetryScreenPhase.PAUSED -> session?.resume()
            else -> Unit
        }
    }

    fun stop() = stopInternal(publishStop = true)

    private fun stopInternal(publishStop: Boolean) {
        val last = session
        currentRunId = "" // Reject even already-queued UI reports.
        session = null
        networkObserver?.close()
        networkObserver = null
        prepared = null
        preparation?.cancel()
        preparation = null
        last?.stop()
        if (publishStop) {
            state.value = AdvancedRetryScreenState(
                phase = AdvancedRetryScreenPhase.STOPPED,
                report = last?.snapshot(),
            )
        }
    }

    override fun onCleared() {
        stopInternal(publishStop = false)
        super.onCleared()
    }
}
