package com.v2ray.ang.service
import com.v2ray.ang.gfwknocker.*

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.AppConfig.TAG_DIRECT
import com.v2ray.ang.R
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.TestRunTracker
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.V2rayConfigUtil
import go.Seq
import kotlinx.coroutines.*
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import rx.Observable
import rx.Subscription
import java.lang.ref.SoftReference
import kotlin.math.min

object V2RayServiceManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_ICON_THRESHOLD = 3000

    @Volatile
    var TLS_serv: backServ? = null

    private val coreStopGate = CoreStopGate()
    // Native cleanup must finish even after an Android Service has been stopped.
    private val nativeCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Live delay JNI is blocking: invalidating the owner drops old results even
    // if the native call cannot be physically interrupted.
    private val liveDelayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveDelayTracker = TestRunTracker()
    private var liveDelayJob: Job? = null

    // Called only while holding V2RayServiceManager's monitor (start/stop/measure).
    private fun invalidateLiveDelay() {
        liveDelayTracker.invalidate()
        liveDelayJob?.cancel()
        liveDelayJob = null
    }

    val v2rayPoint: V2RayPoint = Libv2ray.newV2RayPoint(V2RayCallback(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
    private val mMsgReceive = ReceiveMessageHandler()
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    @Volatile
    var serviceControl: SoftReference<ServiceControl>? = null
        @Synchronized
        set(value) {
            field = value
            val resolved = value?.get()?.getService()
            if (resolved != null) activeServiceRef = resolved
            Seq.setContext(value?.get()?.getService()?.applicationContext)
            Libv2ray.initV2Env(Utils.userAssetPath(value?.get()?.getService()), "")
        }

    // Retain the active Service owner across shutdown so SoftReference GC
    // cannot falsely mark teardown as an unknown owner and quarantine starts.
    @Volatile
    private var activeServiceRef: Service? = null

    @Volatile
    var currentConfig: ServerConfig? = null

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var mSubscription: Subscription? = null
    private var mNotificationManager: NotificationManager? = null

    fun startV2Ray(context: Context) {
        val mode = settingsStorage?.decodeString(AppConfig.PREF_MODE)
        if (VpnModeSelection.requiresVpnPermission(mode) && VpnService.prepare(context) != null) {
            context.toast(R.string.toast_permission_denied)
            return
        }
        if (settingsStorage?.decodeBool(AppConfig.PREF_PROXY_SHARING) == true) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }
        val primary = Intent(context.applicationContext, if (VpnModeSelection.startsProxyProcess(mode)) {
            V2RayProxyOnlyService::class.java
        } else {
            V2RayVpnService::class.java
        })
        val psiphon = if (VpnModeSelection.usesPsiphon(mode)) {
            Intent(context.applicationContext, PsiphonVpnService::class.java)
        } else null
        fun launch(intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        try {
            launch(primary)
            if (psiphon != null) launch(psiphon)
        } catch (error: Exception) {
            Log.e(ANG_PACKAGE, "Unable to start selected VPN service mode", error)
            if (psiphon != null) runCatching { context.stopService(psiphon) }
            runCatching { context.stopService(primary) }
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "")
        }
    }

    private class V2RayCallback : V2RayVPNServiceSupportsSet {
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            // called by go
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, e.toString())
                -1
            }
        }

        override fun prepare(): Long {
            return 0
        }

        override fun protect(l: Long): Boolean {
            val serviceControl = serviceControl?.get() ?: return true
            return serviceControl.vpnProtect(l.toInt())
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            //Logger.d(s)
            return 0
        }

        override fun setup(s: String): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            //Logger.d(s)
            return try {
                serviceControl.startService()
                lastQueryTime = System.currentTimeMillis()
                startSpeedNotification()
                0
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, e.toString())
                -1
            }
        }
    }

    // Serialize native startup with stop's resource snapshot; onRevoke cannot
    // observe an unstarted core and then leave a concurrently started core alive.
    @Synchronized
    fun startV2rayPoint() {
        val service = serviceControl?.get()?.getService()
        if (coreStopGate.isQuarantined()) {
            Log.e(ANG_PACKAGE, "Native stop remained unverified; refusing Xray start")
            cancelNotificationFor(service)
            service?.stopSelf()
            if (service != null) {
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return
        }
        if (service is V2RayVpnService && !service.isLifecycleActive()) {
            Log.e(ANG_PACKAGE, "Refusing Xray VPN start on destroyed V2RayVpnService")
            cancelNotificationFor(service)
            service.stopSelf()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return
        }
        // If stopLoop is still executing, coalesce starts into one deferred retry.
        if (!coreStopGate.requestStart()) return

        if (service == null) return
        val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
            ?.takeIf { it.isNotBlank() }
        if (guid == null) {
            cancelNotificationFor(service)
            service.stopSelf()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            cancelNotificationFor(service)
            service.stopSelf()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return
        }
        if (v2rayPoint.isRunning) return
        // Reopen per-service trackers ONLY after a verified clean stop so
        // deferred restarts on a reused V2RayVpnService establish a fresh TUN.
        if (service is V2RayVpnService && !service.prepareForVerifiedRestart()) {
            Log.e(ANG_PACKAGE, "Previous Xray TUN or tun2socks cleanup is still outstanding")
            cancelNotificationFor(service)
            service.stopSelf()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return
        }
        activeServiceRef = service
        invalidateLiveDelay()
        val result = V2rayConfigUtil.getV2rayConfig(service, guid)
        if (!result.status) {
            cancelNotificationFor(service)
            service.stopSelf()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return
        }

        var receiverRegistered = false
        var fragmentor: backServ? = null
        try {
            // Configuration must be valid before allocating optional local listeners.
            // Read the exact keys used by the existing GFW settings screen.
            val outbound = config.getProxyOutbound()
            val options = runCatching {
                val preferences = my_preference_storage(service)
                FragmentorOptions.fromSettings(
                    outbound?.getServerAddress(), outbound?.getServerPort()
                ) { key, fallback -> preferences.get_value(key, fallback) }
            }.getOrNull()
            if (options != null) {
                fragmentor = backServ(
                    service.applicationContext, service, options.targetHost,
                    options.targetPort, options.fragments, options.delaySeconds,
                    false // HTTPS/DoH is optional; no public setting identifies YouTube mode.
                )
                fragmentor.start_TLS_service()
                fragmentor.start_HTTPS_service()
                TLS_serv = fragmentor
            }

            val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(mMsgReceive, filter, Context.RECEIVER_NOT_EXPORTED)
            } else service.registerReceiver(mMsgReceive, filter)
            receiverRegistered = true

            v2rayPoint.configureFileContent = result.content
            v2rayPoint.domainName = config.getV2rayPointDomainAndPort()
            currentConfig = config
            v2rayPoint.runLoop(settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) ?: false)
            if (!v2rayPoint.isRunning) error("Native core failed to enter running state")

            // Xray's SOCKS listener is ready; Psiphon VPN routing may still be connecting.
            val state = if (VpnModeSelection.usesPsiphon(settingsStorage?.decodeString(AppConfig.PREF_MODE))) {
                AppConfig.MSG_STATE_RUNNING
            } else AppConfig.MSG_STATE_START_SUCCESS
            MessageUtil.sendMsg2UI(service, state, "")
            runCatching { showNotification() }.onFailure { Log.d(ANG_PACKAGE, "Notification: $it") }
        } catch (failure: Exception) {
            cleanupFailedStartup(service, fragmentor, receiverRegistered, failure)
        } catch (failure: LinkageError) {
            // Missing/broken JNI is an Error, not an Exception. It must not
            // strand local forwarding listeners when native startup fails.
            cleanupFailedStartup(service, fragmentor, receiverRegistered, failure)
        }
    }

    /**
     * Start failures may occur AFTER a listener or JNI resource was launched.
     * The synchronous stop requests interrupt listener I/O promptly, but do
     * not prove thread exit. Retain the exact fragmentor before dispatching
     * the existing background stop gate so a new start stays fenced until
     * core, listener/forwarder, tun2socks and TUN close evidence is complete.
     *
     * Invoked under startV2rayPoint's manager monitor. Do not join threads
     * or infer cross-process native completion on this startup/UI path.
     */
    private fun cleanupFailedStartup(
        service: Service,
        fragmentor: backServ?,
        receiverRegistered: Boolean,
        failure: Throwable,
    ) {
        Log.d(ANG_PACKAGE, "Native core start failed: $failure")
        runCatching { fragmentor?.stop_HTTPS_service() }
        runCatching { fragmentor?.stop_TLS_service() }
        // A stop request is not exit evidence. The async stop observer must
        // retain this handle even when failure happened before publication.
        if (fragmentor != null) TLS_serv = fragmentor
        stopV2rayPoint()
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(mMsgReceive) }
        }
        currentConfig = null
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
    }

    /**
     * Atomically authorize a service's shutdown against the current manager owner.
     * This and serviceControl's setter synchronize on V2RayServiceManager.
     * A stale service must not stop its replacement's native core or notification.
     */
    @Synchronized
    fun stopV2rayPointIfOwner(requester: ServiceControl, cancelForeground: Boolean = false): Boolean {
        val activeOwner = serviceControl?.get()
        if (activeOwner != null && activeOwner !== requester) return false
        stopV2rayPoint()
        if (cancelForeground) cancelNotificationFor(requester.getService())
        return true
    }

    @Synchronized
    fun stopV2rayPoint() {
        invalidateLiveDelay()
        // Idempotent while an earlier stopLoop remains in flight.
        val stopToken = coreStopGate.beginStop() ?: return
        val service = serviceControl?.get()?.getService() ?: activeServiceRef
        val fragmentor = TLS_serv
        TLS_serv = null

        nativeCleanupScope.launch {
            var coreStopped = false
            try {
                if (v2rayPoint.isRunning) v2rayPoint.stopLoop()
                coreStopped = !v2rayPoint.isRunning
            } catch (failure: Exception) {
                Log.d(ANG_PACKAGE, "Native stopLoop failed: $failure")
            } catch (failure: LinkageError) {
                // A missing JNI symbol must not skip listener, tun2socks or
                // TUN-owner cleanup. Keep coreStopped=false (unverified).
                Log.e(ANG_PACKAGE, "Native Xray stop unavailable", failure)
            }

            // Closing a listener socket is not proof its Thread exited. The
            // fragmentor requests both stops and checks the bounded exit.
            val fragmentorStopped = runCatching {
                fragmentor?.stopAndAwaitListeners() ?: true
            }.onFailure {
                Log.w(ANG_PACKAGE, "Fragmentor listener cleanup failed")
            }.getOrDefault(false)
            // Closing the Android VpnService's own TUN PFD is independent
            // from tun2socks subprocess termination. The owner must account
            // for all established PFDs and any Builder.establish() in flight.
            // Run this on IO; never wait on the Android lifecycle thread.
            val tunDescriptorClosed = runCatching {
                when (service) {
                    is V2RayVpnService -> service.stopAndAwaitTunDescriptorClose()
                    is V2RayProxyOnlyService -> true // Does not own a VPN TUN.
                    else -> false // Unknown owner cannot supply close evidence.
                }
            }.onFailure {
                Log.w(ANG_PACKAGE, "Xray TUN descriptor close observation unavailable")
            }.getOrDefault(false)

            // Process.destroy() is only a request. Every spawned tun2socks
            // process must be observed exited by its own waitFor() watcher.
            val tunStopped = runCatching {
                when (service) {
                    is V2RayVpnService -> service.stopAndAwaitTun2socksExit()
                    is V2RayProxyOnlyService -> true // No VPN tun2socks subprocess.
                    else -> false // Unknown owner cannot prove VPN child exit.
                }
            }.onFailure {
                Log.w(ANG_PACKAGE, "Xray tun2socks exit observation unavailable")
            }.getOrDefault(false)
            val stopped = coreStopped && fragmentorStopped && tunStopped && tunDescriptorClosed

            // Legacy local status only: owned PFD close return does NOT prove
            // native aliases, per-client handlers, or a cross-process receipt.
            withContext(Dispatchers.Main) {
                if (!fragmentorStopped && fragmentor != null && TLS_serv == null) {
                    // Keep a handle to the unverified listener for diagnostics.
                    // CoreStopGate quarantines new starts on incomplete cleanup.
                    TLS_serv = fragmentor
                }
                if (stopped) {
                    if (activeServiceRef === service) activeServiceRef = null
                    cancelNotificationFor(service)
                    if (service != null) {
                        runCatching { service.unregisterReceiver(mMsgReceive) }
                        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                    }
                    currentConfig = null
                } else if (service != null) {
                    // A failed JNI status query is not evidence of STOPPED and
                    // must not prevent CoreStopGate from recording failure.
                    if (runCatching { v2rayPoint.isRunning }.getOrDefault(false)) {
                        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        // The core might have exited while a listener still
                        // owns resources. Neither Success nor Not Running is
                        // evidence of a fully stopped VPN at that point.
                        Log.w(ANG_PACKAGE, "Xray local teardown incomplete; suppressing not-running state")
                        runCatching {
                            updateNotification(
                                service.getString(R.string.toast_services_failure),
                                0,
                                0,
                            )
                        }
                    }
                }

                val restartToken = coreStopGate.completeStop(stopToken, stopped)
                if (restartToken != null && coreStopGate.canResume(restartToken)) {
                    val currentService = serviceControl?.get()?.getService()
                    if (currentService is V2RayVpnService && !currentService.isLifecycleActive()) {
                        Log.w(ANG_PACKAGE, "Deferred Xray restart observed destroyed V2RayVpnService; launching fresh service")
                        runCatching { startV2Ray(currentService.applicationContext) }.onFailure {
                            Log.e(ANG_PACKAGE, "Unable to relaunch V2RayVpnService after verified stop", it)
                            MessageUtil.sendMsg2UI(currentService, AppConfig.MSG_STATE_START_FAILURE, "")
                        }
                    } else {
                        startV2rayPoint()
                    }
                }
            }
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    //Logger.e("ReceiveMessageHandler", intent?.getIntExtra("key", 0).toString())
                    if (v2rayPoint.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }
                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }
                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }
                AppConfig.MSG_STATE_STOP -> {
                    serviceControl.stopService()
                }
                AppConfig.MSG_STATE_RESTART -> {
                    startV2rayPoint()
                }
                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(ANG_PACKAGE, "SCREEN_OFF, stop querying stats")
                    stopSpeedNotification()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(ANG_PACKAGE, "SCREEN_ON, start querying stats")
                    startSpeedNotification()
                }
            }
        }
    }

    @Synchronized
    private fun measureV2rayDelay() {
        // Only the most recent request for this selected profile can publish.
        invalidateLiveDelay()
        val service = serviceControl?.get()?.getService() ?: return
        val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
            ?.takeIf { it.isNotBlank() }
        if (guid == null || !v2rayPoint.isRunning || coreStopGate.isStopping()) {
            // A rejected request must terminate the UI's "testing" state.
            MessageUtil.sendMsg2UI(
                service,
                AppConfig.MSG_MEASURE_DELAY_SUCCESS,
                service.getString(R.string.connection_test_error, "request rejected")
            )
            return
        }

        val runId = liveDelayTracker.begin(listOf(guid))
        liveDelayJob = liveDelayScope.launch {
            var time = -1L
            var errstr = ""
            try {
                time = v2rayPoint.measureDelay(AppConfig.DelayTestUrl)
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, "measureV2rayDelay: $e")
                errstr = e.message?.substringAfter("\":") ?: "empty message"
            }

            // Serialize this short publish gate with core start/stop so an
            // obsolete JNI completion cannot emit a success after disconnect.
            synchronized(V2RayServiceManager) {
                if (!isActive || !v2rayPoint.isRunning ||
                    serviceControl?.get()?.getService() !== service ||
                    mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER) != guid ||
                    !liveDelayTracker.accepts(runId, guid)
                ) return@synchronized
                val result = if (time == -1L) {
                    service.getString(R.string.connection_test_error, errstr)
                } else {
                    service.getString(R.string.connection_test_available, time)
                }
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)
            }
        }
    }

    /**
     * Satisfy Android O+ startForegroundService() promptly on service entry
     * before any early return, quarantine rejection, or deferred async stop.
     */
    fun ensureForeground(service: Service) {
        runCatching { showNotificationFor(service) }
            .onFailure { Log.d(ANG_PACKAGE, "Ensure foreground notification: $it") }
    }

    private fun showNotification() {
        val service = serviceControl?.get()?.getService() ?: return
        showNotificationFor(service)
    }

    private fun showNotificationFor(service: Service) {
        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service,
                NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            })

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)

        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service,
                NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            })

        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannelFor(service)
                } else {
                    // If earlier version channel ID is not used
                    // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                    ""
                }

        mBuilder = NotificationCompat.Builder(service, channelId)
                .setSmallIcon(R.drawable.ic_stat_name)
            // ------- GFW-knocker  ----------------------
//                .setContentTitle("فعلا فقط یوتیوب")
                .setContentTitle(currentConfig?.remarks ?: service.getString(R.string.app_name))
            // ------- GFW-knocker  ----------------------
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .addAction(R.drawable.ic_close_grey_800_24dp,
                        service.getString(R.string.notification_action_stop_v2ray),
                        stopV2RayPendingIntent)
        //.build()

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)  //取消震动,铃声其他都不好使

        val notification = mBuilder?.build() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val service = serviceControl?.get()?.getService()
        return if (service != null) createNotificationChannelFor(service) else "RAY_NG_M_CH_ID"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannelFor(service: Service): String {
        val channelId = "RAY_NG_M_CH_ID"
        val channelName = "V2rayNG Background Service"
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.DKGRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager = mNotificationManager
            ?: (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also {
                mNotificationManager = it
            }
        manager.createNotificationChannel(chan)
        return channelId
    }

    fun cancelNotification() {
        cancelNotificationFor(serviceControl?.get()?.getService())
    }

    private fun cancelNotificationFor(service: Service?) {
        runCatching { service?.stopForeground(true) }
        mBuilder = null
        mSubscription?.unsubscribe()
        mSubscription = null
    }

    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText) // Emui4.1 need content text even if style is set as BigTextStyle
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = serviceControl?.get()?.getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    private fun startSpeedNotification() {
        if (mSubscription == null &&
                v2rayPoint.isRunning &&
                settingsStorage?.decodeBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            var lastZeroSpeed = false
            val outboundTags = currentConfig?.getAllOutboundTags()
            outboundTags?.remove(TAG_DIRECT)

            mSubscription = Observable.interval(3, java.util.concurrent.TimeUnit.SECONDS)
                    .subscribe {
                        val queryTime = System.currentTimeMillis()
                        val sinceLastQueryInSeconds = (queryTime - lastQueryTime) / 1000.0
                        var proxyTotal = 0L
                        val text = StringBuilder()
                        outboundTags?.forEach {
                            val up = v2rayPoint.queryStats(it, "uplink")
                            val down = v2rayPoint.queryStats(it, "downlink")
                            if (up + down > 0) {
                                appendSpeedString(text, it, up / sinceLastQueryInSeconds, down / sinceLastQueryInSeconds)
                                proxyTotal += up + down
                            }
                        }
                        val directUplink = v2rayPoint.queryStats(TAG_DIRECT, "uplink")
                        val directDownlink = v2rayPoint.queryStats(TAG_DIRECT, "downlink")
                        val zeroSpeed = (proxyTotal == 0L && directUplink == 0L && directDownlink == 0L)
                        if (!zeroSpeed || !lastZeroSpeed) {
                            if (proxyTotal == 0L) {
                                appendSpeedString(text, outboundTags?.firstOrNull(), 0.0, 0.0)
                            }
                            appendSpeedString(text, TAG_DIRECT, directUplink / sinceLastQueryInSeconds,
                                    directDownlink / sinceLastQueryInSeconds)
                            updateNotification(text.toString(), proxyTotal, directDownlink + directUplink)
                        }
                        lastZeroSpeed = zeroSpeed
                        lastQueryTime = queryTime
                    }
        }
    }

    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.substring(0, min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    private fun stopSpeedNotification() {
        if (mSubscription != null) {
            mSubscription?.unsubscribe() //stop queryStats
            mSubscription = null
            updateNotification(currentConfig?.remarks, 0, 0)
        }
    }
}
