package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.*
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogConfigFilterBinding
import com.v2ray.ang.dto.*
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.*
import com.v2ray.ang.util.MmkvManager.KEY_ANG_CONFIGS
import kotlinx.coroutines.*
import java.util.*
import java.security.MessageDigest

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    var serverList = MmkvManager.decodeServerList()
    var subscriptionId: String = ""
    var keywordFilter: String = ""
        private set
    private var profileProbeStatus = ProfileProbeStatus.ALL
    private var profileMaxLatencyMs: Long? = null
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    /** TCP-only row invalidation batches; native probe updates stay unchanged. */
    val updateListBatchAction by lazy { MutableLiveData<List<Int>>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }

    private val tcpingTestScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val testRuns = TestRunTracker()
    private val tcpDisplayRows = TcpRowBatch()
    private val tcpEvidenceByGuid = mutableMapOf<String, TcpProbeEvidence>()
    private val tcpEvidenceRevisions = mutableMapOf<String, String>()
    private var tcpDisplayFlushJob: Job? = null
    private var realPingScheduleJob: Job? = null
    private var nativeTestRunPending = false
    private var broadcastRegistered = false

    private fun probeFilterActive(): Boolean =
        profileProbeStatus != ProfileProbeStatus.ALL || profileMaxLatencyMs != null

    // The digest never stores or prints profile credentials; only matching
    // evidence for the same saved configuration remains eligible for display.
    private fun profileDigest(config: ServerConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Gson().toJson(config).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /**
     * Called on the UI thread. Snapshot identifiers before scheduling any background work.
     * A new run invalidates both previous TCP callbacks and in-flight native callbacks.
     */
    private fun beginTestRun(visibleGuids: List<String>, native: Boolean): String {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        realPingScheduleJob?.cancel()
        SpeedtestUtil.closeAllTcpSockets()
        val runId = testRuns.begin(visibleGuids)
        tcpDisplayFlushJob?.cancel()
        tcpDisplayRows.begin(runId)
        tcpEvidenceByGuid.clear()
        tcpEvidenceRevisions.clear()
        // Starting a TCP-only run must not launch a native service unnecessarily.
        // If a real-ping run preceded it, this command cancels that native run.
        if (native || nativeTestRunPending) {
            MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG_CANCEL, runId)
        }
        nativeTestRunPending = native
        MmkvManager.clearTestDelayResults(visibleGuids)
        // Resetting saved delays changes membership under success/latency filters.
        if (probeFilterActive()) updateCache()
        updateListAction.value = -1
        return runId
    }

    internal fun getTcpEvidence(guid: String): TcpProbeEvidence? {
        val evidence = tcpEvidenceByGuid[guid] ?: return null
        val revision = tcpEvidenceRevisions[guid] ?: return null
        val current = runCatching { MmkvManager.decodeServerConfig(guid) }.getOrNull()
        if (current == null || profileDigest(current) != revision) {
            // A profile was edited/deleted: drop only its old TCP evidence.
            tcpEvidenceByGuid.remove(guid)
            tcpEvidenceRevisions.remove(guid)
            return null
        }
        return evidence
    }

    fun startListenBroadcast() {
        if (broadcastRegistered) return
        isRunning.value = false
        val app = getApplication<AngApplication>()
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(mMsgReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else app.registerReceiver(mMsgReceiver, filter)
        broadcastRegistered = true
        MessageUtil.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onCleared() {
        if (broadcastRegistered) {
            getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
            broadcastRegistered = false
        }
        testRuns.invalidate()
        tcpDisplayFlushJob?.cancel()
        tcpDisplayRows.clear()
        tcpEvidenceByGuid.clear()
        tcpEvidenceRevisions.clear()
        realPingScheduleJob?.cancel()
        // Do not send a global native Cancel from an obsolete ViewModel: a new
        // activity may already own the native service with a newer run token.
        tcpingTestScope.cancel()
        SpeedtestUtil.closeAllTcpSockets()
        Log.i(ANG_PACKAGE, "Main ViewModel is cleared")
        super.onCleared()
    }

    fun reloadServerList() {
        serverList = MmkvManager.decodeServerList()
        updateCache()
        updateListAction.value = -1
    }

    fun removeServer(guid: String): Boolean {
        if (!MmkvManager.removeServer(guid)) {
            getApplication<AngApplication>().toast(R.string.toast_failure)
            reloadServerList()
            return false
        }
        tcpEvidenceByGuid.remove(guid)
        tcpEvidenceRevisions.remove(guid)
        serverList.remove(guid)
        val index = getPosition(guid)
        if(index >= 0){
            serversCache.removeAt(index)
        }
        return true
    }

    fun appendCustomConfigServer(server: String) {
        val config = ServerConfig.create(EConfigType.CUSTOM)
        config.remarks = System.currentTimeMillis().toString()
        config.subscriptionId = subscriptionId
        config.fullConfig = Gson().fromJson(server, V2rayConfig::class.java)
        val key = MmkvManager.encodeServerConfig("", config)
        if (key.isBlank()) {
            getApplication<AngApplication>().toast(R.string.toast_failure)
            return
        }
        serverRawStorage?.encode(key, server)
        serverList.add(0, key)
        serversCache.add(0, ServersCache(key,config))
    }

    fun swapServer(fromPosition: Int, toPosition: Int) {
        val firstGuid = serversCache.getOrNull(fromPosition)?.guid ?: return
        val secondGuid = serversCache.getOrNull(toPosition)?.guid ?: return
        if (MmkvManager.swapServerPositions(firstGuid, secondGuid)) {
            serverList = MmkvManager.decodeServerList()
            Collections.swap(serversCache, fromPosition, toPosition)
        } else {
            // Another writer may have replaced one of the profiles.
            reloadServerList()
        }
    }

    @Synchronized
    fun updateCache() {
        serversCache.clear()
        for (guid in serverList) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (tcpEvidenceByGuid.containsKey(guid) &&
                tcpEvidenceRevisions[guid] != profileDigest(config)) {
                tcpEvidenceByGuid.remove(guid)
                tcpEvidenceRevisions.remove(guid)
            }
            if (subscriptionId.isNotEmpty() && subscriptionId != config.subscriptionId) {
                continue
            }

            if (keywordFilter.isEmpty() || config.remarks.contains(keywordFilter)) {
                val delay = if (profileProbeStatus == ProfileProbeStatus.ALL && profileMaxLatencyMs == null)
                    null else runCatching {
                        MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis
                    }.getOrNull()
                if (ProfileProbeFilter.matches(delay, profileProbeStatus, profileMaxLatencyMs)) {
                    serversCache.add(ServersCache(guid, config))
                }
            }
        }
    }

    fun testAllTcping() {
        val snapshot = synchronized(this) { serversCache.toList() }
        val runId = beginTestRun(snapshot.map { it.guid }, native = false)
        getApplication<AngApplication>().toast(R.string.connection_test_testing)

        // Make an immutable, transport-eligible target list before any worker
        // launches. UDP/custom profiles remain UNKNOWN, not false TCP failures.
        val targets = snapshot.mapNotNull { item ->
            val outbound = item.config.getProxyOutbound() ?: return@mapNotNull null
            if (!TcpProbeEligibility.supportsTcpConnect(
                    item.config.configType.name, outbound.protocol, outbound.streamSettings?.network
                )) return@mapNotNull null
            val address = outbound.getServerAddress() ?: return@mapNotNull null
            val port = outbound.getServerPort() ?: return@mapNotNull null
            Triple(item.guid, address, port)
        }
        // A new run snapshots its concurrency: preferences cannot change active
        // scheduling. At most 16 workers exist, even for thousands of targets.
        val concurrency = BoundedProbeQueue.concurrencyFromStored(
            settingsStorage?.decodeString(AppConfig.PREF_TCP_PROBE_CONCURRENCY)
        )
        val queue = BoundedProbeQueue(targets, concurrency)
        repeat(queue.workerCount) {
            tcpingTestScope.launch {
                while (isActive) {
                    val (guid, address, port) = queue.claim() ?: break
                    if (!testRuns.accepts(runId, guid)) continue
                    val evidence = SpeedtestUtil.tcpingEvidence(address, port)
                    val delay = evidence.delayMs
                    withContext(Dispatchers.Main) {
                        // Share forgetGuid's lock through the final persistent write.
                        testRuns.publishIfCurrent(runId, guid) {
                            val current = MmkvManager.decodeServerConfig(guid)
                            if (current == null) false
                            else {
                                MmkvManager.encodeServerTestDelayMillis(guid, delay)
                                tcpEvidenceByGuid[guid] = evidence
                                tcpEvidenceRevisions[guid] = profileDigest(current)
                                if (probeFilterActive()) {
                                    updateCache()
                                    updateListAction.value = -1
                                } else {
                                    queueTcpDisplayUpdate(runId, guid)
                                }
                                true
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Batch RecyclerView invalidations at ~10 Hz. Persistent delay writes stay
     * in the existing TestRunTracker ownership gate and on the main thread.
     */
    private fun queueTcpDisplayUpdate(runId: String, guid: String) {
        if (!tcpDisplayRows.add(runId, guid)) return
        if (tcpDisplayFlushJob?.isActive == true) return
        tcpDisplayFlushJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(100L)
                val visible = synchronized(this@MainViewModel) { serversCache.map { it.guid } }
                val rows = tcpDisplayRows.drain(runId, visible)
                    .filter { index -> testRuns.accepts(runId, visible[index]) }
                if (rows.isEmpty()) break
                updateListBatchAction.value = rows
            }
        }
    }

    fun testAllRealPing() {
        // Never iterate serversCache on Dispatchers.Default; reloading clears the mutable list.
        val guids = synchronized(this) { serversCache.map { it.guid } }
        val runId = beginTestRun(guids, native = true)
        getApplication<AngApplication>().toast(R.string.connection_test_testing)

        realPingScheduleJob = viewModelScope.launch(Dispatchers.Default) {
            for (guid in guids) {
                if (!isActive || !testRuns.accepts(runId, guid)) break
                // A native test may spend time generating configuration. Carry the
                // exact saved profile revision across the test-service boundary.
                val before = MmkvManager.decodeServerConfig(guid) ?: continue
                val revision = profileDigest(before)
                val config = V2rayConfigUtil.getV2rayConfig(getApplication(), guid)
                val unchanged = MmkvManager.decodeServerConfig(guid)
                    ?.let { profileDigest(it) } == revision
                if (config.status && unchanged && isActive && testRuns.accepts(runId, guid)) {
                    MessageUtil.sendMsg2TestService(
                        getApplication(),
                        AppConfig.MSG_MEASURE_CONFIG,
                        ConfigTestRequest(guid, config.content, runId, revision),
                    )
                }
            }
        }
    }

    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    fun filterConfig(context :Context) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val listId = subscriptions.map { it.first }.toList().toMutableList()
        val listRemarks = subscriptions.map { it.second.remarks }.toList().toMutableList()
        listRemarks += context.getString(R.string.filter_config_all)
        val checkedItem = if (subscriptionId.isNotEmpty()) {
            listId.indexOf(subscriptionId)
        } else {
            listRemarks.count() - 1
        }

        val ivBinding = DialogConfigFilterBinding.inflate(LayoutInflater.from(context))
        ivBinding.spSubscriptionId.adapter = ArrayAdapter<String>( context, android.R.layout.simple_spinner_dropdown_item, listRemarks)
        ivBinding.spSubscriptionId.setSelection(checkedItem)
        ivBinding.etKeyword.text = Utils.getEditable(keywordFilter)
        val statuses = context.resources.getStringArray(R.array.profile_probe_statuses).toList()
        ivBinding.spTestStatus.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, statuses)
        ivBinding.spTestStatus.setSelection(profileProbeStatus.ordinal)
        ivBinding.etMaxLatency.setText(profileMaxLatencyMs?.toString() ?: "")
        val builder = AlertDialog.Builder(context).setView(ivBinding.root)
        builder.setTitle(R.string.title_filter_config)
        builder.setPositiveButton(R.string.tasker_setting_confirm, null)
        val dialog = builder.show()
        // Override click listener after show() so invalid latency keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val rawLatency = ivBinding.etMaxLatency.text.toString().trim()
                val parsedLatency = if (rawLatency.isEmpty()) null else rawLatency.toLongOrNull()
                if (rawLatency.isNotEmpty() && (parsedLatency == null || parsedLatency !in 1..60_000)) {
                    android.widget.Toast.makeText(context, R.string.profile_probe_invalid_latency,
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val position = ivBinding.spSubscriptionId.selectedItemPosition
                subscriptionId = if (position < 0 || listRemarks.count() - 1 == position) {
                    ""
                } else {
                    subscriptions[position].first
                }
                keywordFilter = ivBinding.etKeyword.text.toString()
                profileProbeStatus = ProfileProbeStatus.values()
                    .getOrNull(ivBinding.spTestStatus.selectedItemPosition)
                    ?: ProfileProbeStatus.ALL
                profileMaxLatencyMs = parsedLatency
                reloadServerList()

                dialog.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
//        AlertDialog.Builder(context)
//            .setSingleChoiceItems(listRemarks.toTypedArray(), checkedItem) { dialog, i ->
//                try {
//                    subscriptionId = if (listRemarks.count() - 1 == i) {
//                        ""
//                    } else {
//                        subscriptions[i].first
//                    }
//                    reloadServerList()
//                    dialog.dismiss()
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                }
//            }.show()
    }

    fun getPosition(guid: String) : Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    getApplication<AngApplication>().toast(R.string.toast_services_success)
                    isRunning.value = true
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    getApplication<AngApplication>().toast(R.string.toast_services_failure)
                    isRunning.value = false
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val result = intent.getSerializableExtra("content") as? ConfigTestResult ?: return
                    val published = testRuns.publishIfCurrent(result.runId, result.guid) {
                        MmkvManager.encodeServerRealPingEvidence(
                            result.guid, result.delayMillis,
                            result.profileRevision, result.runId,
                        )
                    }
                    if (published) {
                        if (probeFilterActive()) {
                            updateCache()
                            updateListAction.value = -1
                        } else {
                            updateListAction.value = getPosition(result.guid)
                        }
                    }
                }
            }
        }
    }
}
