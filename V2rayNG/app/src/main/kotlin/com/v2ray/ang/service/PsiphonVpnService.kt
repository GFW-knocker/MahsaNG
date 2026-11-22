package com.v2ray.ang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import ca.psiphon.PsiphonTunnel
import ca.psiphon.Tun2SocksJniLoader
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ERoutingMode
import com.v2ray.ang.gfwknocker.my_preference_storage
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.UnderlyingNetworkOwner
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN service that chains V2Ray (proxy mode) -> Psiphon (VPN mode with tun2socks).
 *
 * Runs in its own process (:PsiphonDaemon) because Psiphon and V2Ray both use
 * gomobile native libraries (libgojni.so / libpsiphontunnel.so) with conflicting
 * Go runtime singletons. Separate processes each get their own classloader + JNI space.
 *
 * V2Ray runs separately in :RunSoLibV2RayDaemon as V2RayProxyOnlyService.
 *
 * Traffic flow:
 *   Device apps
 *     -> VPN TUN interface (captured by Android VPN)
 *     -> tun2socks (bridges TUN to SOCKS proxy)
 *     -> Psiphon local SOCKS proxy (localhost:psiphonSocksPort)
 *     -> Psiphon tunnel (encrypted, obfuscated)
 *     -> V2Ray local SOCKS proxy (localhost:v2raySocksPort, configured as Psiphon's upstream)
 *     -> V2Ray outbound (to V2Ray server)
 *     -> Internet
 *
 * Startup sequence (matches psiphon-android's proven order):
 * 1. V2RayServiceManager starts V2RayProxyOnlyService (SOCKS5 on localhost:10808)
 * 2. V2RayServiceManager starts this PsiphonVpnService
 * 3. We wait for V2Ray SOCKS port to become reachable
 * 4. We establish the VPN TUN interface FIRST (so protect()/bindToDevice works)
 * 5. Psiphon tunnel starts with UpstreamProxyURL=socks5://127.0.0.1:10808
 * 6. Psiphon opens its own local SOCKS proxy (onListeningSocksProxyPort callback)
 * 7. When Psiphon connects (onConnected), we start tun2socks -> Psiphon SOCKS port
 */
class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    companion object {
        private const val TAG = "PsiphonVpnService"
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "10.0.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "10.0.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fd00::1"
        private const val PRIVATE_VLAN6_ROUTER = "fd00::2"
        private const val UDPGW_SERVER_ADDRESS = "127.0.0.1:7300"
        private const val V2RAY_SOCKS_PORT_CHECK_INTERVAL_MS = 500L
        private const val V2RAY_SOCKS_PORT_CHECK_TIMEOUT_MS = 30_000L
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_CHANNEL_ID = "PSIPHON_CH_ID"

        const val ACTION_STOP = "com.v2ray.ang.action.STOP_PSIPHON"
        private const val NOTIFICATION_STOP_SCHEME = "mahsang-psiphon-notification-stop"

        val isPsiphonRunning = AtomicBoolean(false)
        val psiphon_http_port = AtomicInteger(0)

        fun psiphon_is_running():Boolean{
            return isPsiphonRunning.get()
        }

        fun get_psiphon_http_port(): Int{
            return psiphon_http_port.get()
        }
    }

    private val settingsStorage by lazy {
        MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE)
    }

    private val mystrg by lazy { my_preference_storage() }
    private val usesUpstreamProxy by lazy {
        VpnModeSelection.usesPsiphon(settingsStorage?.decodeString(AppConfig.PREF_MODE)) ||
            mystrg.get_value("sw_psiphon_after", "") == "true"
    }
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AppConfig.BROADCAST_ACTION_SERVICE) return
            if (intent.getIntExtra("key", 0) == AppConfig.MSG_STATE_STOP) {
                Thread { stopAll() }.start()
            } else if (intent.getIntExtra("key", 0) == AppConfig.MSG_REGISTER_CLIENT &&
                isRunning.get() && !teardownStarted.get()
            ) {
                // Keep UI aware if Psiphon still owns the VPN TUN even when Xray died.
                MessageUtil.sendMsg2UI(this@PsiphonVpnService, AppConfig.MSG_STATE_RUNNING, "")
            }
        }
    }
    private var stopReceiverRegistered = false

    private var psiphonTunnel: PsiphonTunnel? = null
    private var mInterface: ParcelFileDescriptor? = null
    // Exact Java TUN PFD ownership persists until close() actually returns.
    private val tunCloseTracker = TunDescriptorCloseTracker<ParcelFileDescriptor>()
    @Volatile private var tun2socksThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val startGate = PsiphonStartGate()
    private val routeGate = PsiphonRouteGate()
    private val teardownStarted = AtomicBoolean(false)
    // Do not retry TUN close from onDestroy while the first teardown is
    // still signalling JNI or stopping the native tunnel.
    private val stopCleanupFinished = AtomicBoolean(false)
    private val startCommandAccepted = AtomicBoolean(false)
    private val notificationStopTokenGate = NotificationStopTokenGate()
    private val notificationStopToken = UUID.randomUUID().toString()
    @Volatile private var activeStopPendingIntent: PendingIntent? = null
    private val psiphonSocksPort = AtomicInteger(0)

    private val psiphonHttpPort = AtomicInteger(0)


    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // The Psiphon service is isolated in its own Android process. Keep its
    // network-owner epoch local to this service instance, just as Xray does.
    private val underlyingNetworkOwner = UnderlyingNetworkOwner<Network>()
    private val underlyingNetworkHandler = Handler(Looper.getMainLooper())
    private var registeredUnderlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Network callbacks may be delivered late, or queued while another
     * underlying network replaces the old one. Never publish stale events.
     */
    private fun postUnderlyingNetworkUpdate(update: UnderlyingNetworkOwner.Update<Network>?) {
        if (update == null) return
        underlyingNetworkHandler.post {
            underlyingNetworkOwner.publishIfCurrent(update) { network ->
                runCatching {
                    setUnderlyingNetworks(network?.let { arrayOf(it) })
                }.onFailure {
                    Log.w(TAG, "Unable to publish the current underlying network")
                }
            }
        }
    }

    @Synchronized
    private fun registerUnderlyingNetworkCallback() {
        releaseUnderlyingNetworkCallback()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val epoch = underlyingNetworkOwner.begin()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                postUnderlyingNetworkUpdate(underlyingNetworkOwner.onAvailable(epoch, network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                postUnderlyingNetworkUpdate(underlyingNetworkOwner.onCapabilitiesChanged(epoch, network))
            }

            override fun onLost(network: Network) {
                postUnderlyingNetworkUpdate(underlyingNetworkOwner.onLost(epoch, network))
            }
        }
        registeredUnderlyingNetworkCallback = callback
        try {
            connectivity.requestNetwork(defaultNetworkRequest, callback)
        } catch (_: Exception) {
            registeredUnderlyingNetworkCallback = null
            postUnderlyingNetworkUpdate(underlyingNetworkOwner.stop())
            Log.w(TAG, "Unable to register Psiphon underlying network observer")
        }
    }

    @Synchronized
    private fun releaseUnderlyingNetworkCallback() {
        val callback = registeredUnderlyingNetworkCallback
        registeredUnderlyingNetworkCallback = null
        // Invalidate queued callbacks on every API level, but only publish to
        // the Android VPN underlay API where this observer is actually supported.
        val stopUpdate = underlyingNetworkOwner.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            postUnderlyingNetworkUpdate(stopUpdate)
            if (callback != null) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                    .onFailure { Log.w(TAG, "Unable to unregister Psiphon underlying network observer") }
            }
        }
    }

    // =========================================================================
    // Android Service lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        check(notificationStopTokenGate.arm(notificationStopToken)) { "Invalid notification stop identity" }
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else registerReceiver(stopReceiver, filter)
        stopReceiverRegistered = true
    }

    override fun onRevoke() {
        stopAll()
    }

    override fun onDestroy() {
        notificationStopTokenGate.invalidate()
        if (stopReceiverRegistered) {
            runCatching { unregisterReceiver(stopReceiver) }
            stopReceiverRegistered = false
        }
        // VpnService may be destroyed without ACTION_STOP or onRevoke.
        // A stale notification can create and destroy a service that has
        // never started a tunnel; it must not stop another session's proxy.
        val alreadyStopping = teardownStarted.get()
        if (startCommandAccepted.get()) stopAll(isForced = false)
        // A close exception during Stop must not erase the sole owned PFD.
        // Retry only when the first native teardown has already finished,
        // and do not reissue JNI termination or claim a new native receipt.
        if (alreadyStopping && stopCleanupFinished.get() &&
            !closeOwnedTunDescriptors()
        ) Log.w(TAG, "Psiphon TUN closure still unverified during destroy")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // An old PendingIntent may be delivered after Android replaces the
            // service instance. Only the current notification may stop it.
            val stopData = intent.data
            if (stopData?.scheme != NOTIFICATION_STOP_SCHEME ||
                !notificationStopTokenGate.accepts(stopData?.lastPathSegment)
            ) {
                Log.w(TAG, "Ignoring stale Psiphon notification stop")
                return if (startCommandAccepted.get() && !teardownStarted.get()) START_STICKY else START_NOT_STICKY
            }
            stopAll()
            return START_NOT_STICKY
        }
        if (teardownStarted.get()) {
            // Satisfy startForegroundService() before stopping a terminating instance.
            runCatching { startForegroundNotification() }
            runCatching { stopForeground(true) }
            stopSelf()
            return START_NOT_STICKY
        }
        startCommandAccepted.set(true)
        if (VpnService.prepare(this) != null) {
            Log.w(TAG, "VPN authorization unavailable; refusing to start")
            runCatching { startForegroundNotification() }
            stopAll()
            return START_NOT_STICKY
        }
        // Start foreground promptly on API 26+ before asynchronous startup.
        startForegroundNotification()
        // Terminal teardown and start issuance share the exact same monitor.
        // An onRevoke() racing this command cannot resurrect the service.
        val attempt = synchronized(startGate) {
            if (teardownStarted.get()) null else startGate.beginStart()
        } ?: return if (teardownStarted.get()) START_NOT_STICKY else START_STICKY
        startPsiphonTunnelAsync(attempt)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    // =========================================================================
    // Psiphon tunnel management
    // =========================================================================

    private fun startPsiphonTunnelAsync(attempt: Long) {
        Thread {
            try {

                if ( usesUpstreamProxy) {
                    // Wait for V2Ray's SOCKS proxy to become reachable
                    val v2raySocksPort = Utils.parseInt(
                        settingsStorage?.decodeString(AppConfig.PREF_SOCKS_PORT),
                        AppConfig.PORT_SOCKS.toInt()
                    )
                    Log.i(TAG, "Waiting for V2Ray SOCKS proxy on port $v2raySocksPort...")

                    if (!waitForPort(v2raySocksPort, attempt)) {
                        if (startGate.isCurrent(attempt)) {
                            Log.e(TAG, "V2Ray SOCKS port $v2raySocksPort unavailable; stopping")
                            stopAll()
                        }
                        return@Thread
                    }
                }

                // IMPORTANT: Establish VPN TUN interface BEFORE starting Psiphon.
                // This matches psiphon-android's proven startup order:
                //   1. VPN TUN established (so Android knows this is a VPN service)
                //   2. Psiphon starts (bindToDevice/protect calls work correctly)
                //   3. tun2socks starts later when Psiphon reports its SOCKS port
                //
                // Without the VPN TUN, protect() calls in bindToDevice() may be
                // no-ops because Android doesn't recognize us as an active VPN yet.
                // Stop/revoke cannot race a stale waiter into re-creating the VPN TUN.
                synchronized(startGate) {
                    if (!startGate.isCurrent(attempt)) return@Thread
                    Log.i(TAG, "Establishing VPN TUN interface before starting Psiphon...")
                    establishVpnInterface()
                }
                if (!startGate.isCurrent(attempt)) return@Thread

                val tunnel = PsiphonTunnel.newPsiphonTunnel(this)
                synchronized(startGate) {
                    if (!startGate.isCurrent(attempt)) {
                        runCatching { tunnel.stop() }
                        return@Thread
                    }
                    psiphonTunnel = tunnel
                }
                if (!startGate.isCurrent(attempt)) return@Thread

                Log.i(TAG, "Starting Psiphon tunnel...")
                tunnel.setClientPlatformAffixes("", "")
                tunnel.setVpnMode(true)
                tunnel.startTunneling(getEmbeddedServerEntries())
                // If stop occurred during native startup, do not leave a stale tunnel.
                if (!startGate.isCurrent(attempt)) runCatching { tunnel.stop() }
            } catch (e: LinkageError) {
                Log.e(TAG, "Psiphon native runtime unavailable", e)
                if (startGate.isCurrent(attempt)) stopAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Psiphon tunnel: ${e.message}", e)
                if (startGate.isCurrent(attempt)) stopAll()
            }
        }.start()
    }

    /**
     * Wait for a TCP port on localhost to become reachable.
     * Returns true if connected within timeout, false otherwise.
     */
    private fun waitForPort(port: Int, attempt: Long): Boolean {
        val deadline = System.currentTimeMillis() + V2RAY_SOCKS_PORT_CHECK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && startGate.isCurrent(attempt)) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return true
                }
            } catch (e: Exception) {
                // Port not ready yet
                Thread.sleep(V2RAY_SOCKS_PORT_CHECK_INTERVAL_MS)
            }
        }
        return false
    }

    // =========================================================================
    // VPN TUN interface + tun2socks setup
    // =========================================================================

    /**
     * Establish the VPN TUN interface. Called BEFORE starting Psiphon so that
     * Android recognizes this service as an active VPN and protect()/bindToDevice()
     * calls work correctly during tunnel establishment.
     *
     * tun2socks is NOT started here — it starts later in onConnected() when
     * Psiphon's local SOCKS proxy port is known.
     */
    private fun establishVpnInterface() {
        val routingMode = settingsStorage?.decodeString(AppConfig.PREF_ROUTING_MODE)
            ?: ERoutingMode.GLOBAL_PROXY.value

        val builder = Builder()
        builder.setMtu(VPN_MTU)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 24)

        if (routingMode == ERoutingMode.BYPASS_LAN.value ||
            routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value
        ) {
            applicationContext.resources.getStringArray(R.array.bypass_private_ip_address).forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            if (routingMode == ERoutingMode.BYPASS_LAN.value ||
                routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value
            ) {
                builder.addRoute("2000::", 3)
            } else {
                builder.addRoute("::", 0)
            }
        }

        // DNS: always use the tun2socks gateway as DNS server.
        // tun2socks intercepts DNS queries (UDP port 53 to the gateway) and tunnels
        // them via the udpgw protocol through Psiphon to the server's DNS resolver.
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addDnsServer(PRIVATE_VLAN6_ROUTER)
        }

        builder.setSession("Psiphon Tunnel")

        // Exclude MahsaNG app to prevent VPN routing loops
        if ( usesUpstreamProxy) {
            if (settingsStorage?.decodeBool(AppConfig.PREF_PER_APP_PROXY) == true) {
                val bypassApps = settingsStorage?.decodeBool(AppConfig.PREF_BYPASS_APPS) ?: false
                if (bypassApps) {
                    builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
                }
            } else {
                builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
            }
        }

        // Per-app proxy settings
        if (settingsStorage?.decodeBool(AppConfig.PREF_PER_APP_PROXY) == true) {
            val apps = settingsStorage?.decodeStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            val bypassApps = settingsStorage?.decodeBool(AppConfig.PREF_BYPASS_APPS) ?: false
            apps?.forEach {
                try {
                    if (bypassApps)
                        builder.addDisallowedApplication(it)
                    else
                        builder.addAllowedApplication(it)
                } catch (e: PackageManager.NameNotFoundException) {
                    // ignored
                }
            }
        }

        // Never discard an earlier owner if its close failed. This setup runs
        // under startGate's monitor; Stop cannot overtake TUN publication.
        val oldInterface = mInterface
        if (oldInterface != null && !closeTrackedTunDescriptor(oldInterface)) {
            throw IllegalStateException("Previous Psiphon VPN TUN closure unverified")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (!tunCloseTracker.beginEstablish()) {
            throw IllegalStateException("Psiphon VPN TUN establishment blocked by Stop")
        }
        var establishmentPending = true
        try {
            val established = builder.establish()
                ?: throw IllegalStateException("Unable to establish Psiphon VPN TUN")
            val accepted = synchronized(tunCloseTracker) {
                val allowed = tunCloseTracker.registered(established)
                establishmentPending = false
                if (allowed) mInterface = established
                allowed
            }
            if (!accepted) {
                // Even a late rejected PFD remains owned until close returns.
                closeTrackedTunDescriptor(established)
                throw IllegalStateException("Psiphon Stop won TUN establishment")
            }
        } catch (failure: Exception) {
            if (establishmentPending) tunCloseTracker.establishFailed()
            throw failure
        }
        // Only start observing the underlay once Android has established our TUN.
        registerUnderlyingNetworkCallback()
        isRunning.set(true)
        // TUN creation is not a connected tunnel; publish running state only
        // after Psiphon's SOCKS callback and tun2socks startup.
        Log.i(TAG, "VPN TUN interface established (tun2socks will start after Psiphon connects)")

    }

    /**
     * Start tun2socks to route VPN TUN traffic through Psiphon's local SOCKS proxy.
     * Called from onConnected() when the Psiphon SOCKS proxy port is known.
     */
    private fun startTun2socksRouting(port: Int) {
        // Ignore late native callbacks after stop/revoke/destroy.
        if (teardownStarted.get() || !startGate.isActive()) return
        if (port <= 0) {
            Log.e(TAG, "Psiphon SOCKS port not available yet, cannot start tun2socks")
            return
        }
        if (mInterface == null) {
            Log.e(TAG, "VPN TUN interface not established, cannot start tun2socks")
            return
        }

        try {
            // Atomic with stopAll(): no detached TUN fd or new routing worker
            // can be published after teardown has made this instance terminal.
            synchronized(startGate) {
                if (teardownStarted.get() || !startGate.isActive()) return
                startTun2socks(port)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to attach VPN TUN to Psiphon JNI routing", error)
            Thread { stopAll() }.start()
            return
        }
        if (teardownStarted.get() || !startGate.isActive()) return
        // Worker creation is not proof of packet forwarding. Until the native
        // bridge exposes a genuine ready/forwarding signal, never claim connected.
        updateNotification("Psiphon routing starting; connectivity unverified")
        Log.i(TAG, "Psiphon routing worker started, not yet connectivity-verified")
    }

    /**
     * Start Psiphon's tun2socks (JNI) to bridge the VPN TUN interface to
     * Psiphon's local SOCKS proxy with udpgw transparent DNS support.
     *
     * The call to runTun2Socks() blocks, so it runs on a dedicated thread.
     * DNS queries are intercepted by tun2socks (via udpgw transparent DNS)
     * and resolved by the Psiphon server's own DNS resolvers.
     */
    private fun startTun2socks(socksPort: Int) {
        // This monitor is shared with stopAll's terminal state transition.
        synchronized(startGate) {
            if (teardownStarted.get() || !startGate.isActive()) return
            val iface = mInterface ?: throw IllegalStateException("Psiphon VPN TUN is closed")

            val socksServerAddress = "127.0.0.1:$socksPort"
            val ipv6Address = if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
                PRIVATE_VLAN6_ROUTER
            } else null

            // The duplicate belongs to the worker/native bridge. Do not detach
            // unless we can create and register the worker before teardown.
            val tunFd = iface.dup().detachFd()
            var nativeLease: PsiphonNativeWorkerGate.Lease? = null
            var workerStarted = false
            try {
                // Arm native termination before publishing the worker. The
                // returned lease belongs only to this exact native attempt.
                val acquiredLease = PsiphonNativeWorkerGate.acquire {
                    Tun2SocksJniLoader.prepareTun2Socks()
                } ?: throw IllegalStateException("Previous routing worker is still alive")
                nativeLease = acquiredLease
                val worker = Thread {
                    try {
                        if (teardownStarted.get() || !startGate.isActive()) {
                            // Teardown won the race before native entry: this
                            // descriptor was not handed to JNI and must be closed.
                            ParcelFileDescriptor.adoptFd(tunFd).close()
                            return@Thread
                        }
                        Tun2SocksJniLoader.runTun2Socks(
                            tunFd,
                            VPN_MTU,
                            PRIVATE_VLAN4_ROUTER,
                            "255.255.255.0",
                            ipv6Address,
                            socksServerAddress,
                            UDPGW_SERVER_ADDRESS,
                            1
                        )
                        Log.i(TAG, "Psiphon tun2socks native worker returned")
                    } catch (error: LinkageError) {
                        Log.e(TAG, "Psiphon JNI linkage unavailable", error)
                    } catch (error: Exception) {
                        Log.e(TAG, "Psiphon JNI routing failed", error)
                    } finally {
                        // A late completion cannot release a newer JNI lease.
                        if (!PsiphonNativeWorkerGate.release(acquiredLease)) {
                            Log.e(TAG, "Psiphon JNI worker returned with a stale lease")
                        }
                        if (!teardownStarted.get()) {
                            // Never preserve a VPN with no packet-routing worker.
                            Thread { stopAll() }.start()
                        }
                    }
                }.apply { name = "PsiphonTun2Socks"; isDaemon = true }
                tun2socksThread = worker
                worker.start()
                workerStarted = true
            } catch (failure: Throwable) {
                tun2socksThread = null
                if (!workerStarted) {
                    nativeLease?.let { PsiphonNativeWorkerGate.release(it) }
                    runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                }
                throw failure
            }
        }
    }

    // =========================================================================
    // Foreground notification (separate from V2Ray's notification)
    // =========================================================================

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Psiphon Tunnel",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Psiphon VPN tunnel notification"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, PsiphonVpnService::class.java).apply {
            action = ACTION_STOP
        }
        // Intent.data participates in PendingIntent identity; extras alone
        // would update an old token under FLAG_UPDATE_CURRENT.
        stopIntent.data = Uri.parse("$NOTIFICATION_STOP_SCHEME://session/$notificationStopToken")
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        activeStopPendingIntent = stopPendingIntent

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Psiphon tunnel connecting...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_close_grey_800_24dp, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
            // Always preserve the session-scoped Stop action across notification updates.
            val stopPendingIntent = activeStopPendingIntent ?: PendingIntent.getService(
                this,
                0,
                Intent(this, PsiphonVpnService::class.java).apply {
                    action = ACTION_STOP
                    data = Uri.parse("$NOTIFICATION_STOP_SCHEME://session/$notificationStopToken")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).also { activeStopPendingIntent = it }
            builder.addAction(R.drawable.ic_close_grey_800_24dp, "Stop", stopPendingIntent)
            val notification = builder.build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.d(TAG, "psiphon notification builder error: $e")
        }
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    /**
     * Only the returned close() of this exact PFD can release its local owner.
     * Native/duplicated FD aliases may remain live after this result.
     */
    private fun closeTrackedTunDescriptor(descriptor: ParcelFileDescriptor): Boolean =
        synchronized(tunCloseTracker) {
            if (!tunCloseTracker.isOutstanding(descriptor)) return@synchronized true
            val closed = runCatching { descriptor.close() }
                .onFailure { Log.w(TAG, "Psiphon VPN TUN close failed", it) }
                .isSuccess
            if (closed) {
                tunCloseTracker.closeReturned(descriptor)
                if (mInterface === descriptor) mInterface = null
            }
            closed
        }

    /** Nonblocking lifecycle cleanup; every failed close remains retriable. */
    private fun closeOwnedTunDescriptors(): Boolean {
        val owned = tunCloseTracker.requestStop()
        var allReturned = true
        owned.forEach { descriptor ->
            if (!closeTrackedTunDescriptor(descriptor)) allReturned = false
        }
        return allReturned && tunCloseTracker.isStopComplete()
    }

    private fun stopAll(isForced: Boolean = true) {
        // Terminate start issuance and TUN-worker registration atomically.
        synchronized(startGate) {
            if (!teardownStarted.compareAndSet(false, true)) return
            startGate.stop()
            routeGate.stop()
        }
        notificationStopTokenGate.invalidate()
        isRunning.set(false)
        isPsiphonRunning.set(false)
        psiphonSocksPort.set(0)
        psiphonHttpPort.set(0)
        psiphon_http_port.set(0)
        runCatching { mystrg.put_value("is_psiphon_running", "n") }
            .onFailure { Log.w(TAG, "Could not persist tunnel stopped state") }

        releaseUnderlyingNetworkCallback()

        // A missing JNI module throws LinkageError, not Exception: continue
        // closing the TUN and upstream proxy even when native termination fails.
        val jniTerminationSignalReturned = runCatching { Tun2SocksJniLoader.terminateTun2Socks() }
            .onFailure { Log.w(TAG, "Psiphon JNI termination signal failed") }
            .isSuccess
        val worker = tun2socksThread
        runCatching {
            if (worker != null && worker !== Thread.currentThread()) worker.join(3000)
        }.onFailure { failure ->
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Psiphon routing worker join failed")
        }
        // A bounded join is not a native completion acknowledgment.
        // Keep ownership of any still-live worker; its finally block
        // retains the process-wide native start gate until actual exit.
        val workerExited = worker == null ||
            (worker !== Thread.currentThread() && !worker.isAlive)
        if (workerExited) tun2socksThread = null
        else Log.w(TAG, "Psiphon routing worker still alive after stop request")

        // Clear the handle before stopping so teardown is safe if the native
        // callback concurrently calls onExiting().
        val tunnel = psiphonTunnel
        psiphonTunnel = null
        val tunnelStopReturned = runCatching { tunnel?.stop() }
            .onFailure { Log.e(TAG, "Native Psiphon tunnel stop failed", it) }
            .isSuccess

        // A close exception is NOT evidence of closure. Keep the exact PFD
        // for a later destroy-time retry rather than discarding ownership.
        val tunCloseReturned = closeOwnedTunDescriptors()
        // Closing Psiphon must not leave a live unprotected upstream SOCKS process.
        if (usesUpstreamProxy) {
            runCatching { stopService(Intent(this, V2RayProxyOnlyService::class.java)) }
                .onFailure { Log.w(TAG, "Unable to stop upstream proxy process") }
        }
        val evidence = PsiphonTeardownEvidence(
            workerRegistered = worker != null,
            workerExited = workerExited,
            jniTerminationSignalReturned = jniTerminationSignalReturned,
            psiphonTunnelStopReturned = tunnelStopReturned,
            tunCloseReturned = tunCloseReturned,
            dependsOnXray = usesUpstreamProxy,
            // A returned PsiphonTunnel.stop() is not a native completion receipt.
            psiphonNativeStopAcknowledged = false,
            // stopService() is only a request; no Xray native receipt exists.
            xrayNativeStopAcknowledged = false,
        )
        if (!evidence.wholeChainCompletionEligible && usesUpstreamProxy) {
            Log.i(TAG, "Upstream Xray native stop remains unverified")
        }
        if (evidence.localStopReportable) {
            // A legacy local-service status, NOT a full-chain session ack.
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        } else {
            Log.w(TAG, "Psiphon local native cleanup incomplete; withholding Stop Success")
            // A live worker may still own a duplicate TUN descriptor, even
            // after mInterface.close(). Do not announce not-running until
            // both worker exit and local TUN close were observed.
            if (workerExited && tunCloseReturned) {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_NOT_RUNNING, "")
            }
        }
        stopCleanupFinished.set(true)
        if (evidence.localStopReportable) {
            runCatching { stopForeground(true) }
        }
        if (isForced) stopSelf()
    }

    // =========================================================================
    // PsiphonTunnel.HostService implementation
    // =========================================================================

    override fun loadLibrary(library: String?) {
        require(!library.isNullOrBlank()) { "library name is required" }
        Log.i(TAG, "loadLibrary($library)")
        System.loadLibrary(library)
    }

    override fun getContext(): Context {
        return this
    }

    override fun getPsiphonConfig(): String {
        val config = JSONObject()

        // Load base config from assets if available
        try {
            val tp10 = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
            val baseConfig = JSONObject(tp10)
            val keys = baseConfig.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                config.put(key, baseConfig.get(key))
            }
        } catch (e: Exception) {
            Log.w(TAG, "No psiphon_config.json in assets, using defaults")
        }



        if ( usesUpstreamProxy) {
            // Set upstream proxy to V2Ray's SOCKS5 port
            val v2raySocksPort = Utils.parseInt(
                settingsStorage?.decodeString(AppConfig.PREF_SOCKS_PORT),
                AppConfig.PORT_SOCKS.toInt()
            )
            config.put("UpstreamProxyURL", "socks5://127.0.0.1:$v2raySocksPort")
            Log.d(TAG, "Psiphon config: UpstreamProxyURL=socks5://127.0.0.1:$v2raySocksPort")
        }

        val countryCode = mystrg.get_value("psiphon_country_code","")
        if(countryCode.isNotEmpty()) {
            if(countryCode.length == 2) {
                config.put("EgressRegion", countryCode)
            }
        }


        val aggressiveMode = mystrg.get_value("psiphon_aggressive","OFF")
        if(aggressiveMode!=null) {
            if(aggressiveMode.equals("ON",true)) {
                config.put("AggressiveEstablishment", true);
                println("psiphon aggressive mode ON")
            }else{
                println("psiphon aggressive mode OFF")
            }
        }


        val psiProtocol = mystrg.get_value("psiphon_protocol","auto")
        if(psiProtocol!=null) {
            if(psiProtocol.equals("normal",true)) {

                val limitProtocols = JSONArray()
                limitProtocols.put("SSH")
                limitProtocols.put("OSSH")
                limitProtocols.put("TLS-OSSH")
                limitProtocols.put("QUIC-OSSH")
                limitProtocols.put("SHADOWSOCKS-OSSH")
                config.put("LimitTunnelProtocols", limitProtocols)

                println("psiphon protocol = normal")

            }else if(psiProtocol.equals("conduit",true)) {

                val limitProtocols = JSONArray()
                limitProtocols.put("INPROXY-WEBRTC-OSSH")
                limitProtocols.put("INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH")
                limitProtocols.put("INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH")
                limitProtocols.put("INPROXY-WEBRTC-FRONTED-MEEK-OSSH")
                limitProtocols.put("INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH")
                limitProtocols.put("INPROXY-WEBRTC-QUIC-OSSH")
                config.put("LimitTunnelProtocols", limitProtocols)

                println("psiphon protocol = conduit")

            }else{
                println("psiphon protocol = auto")
            }
        }


        val ports = PsiphonProxyPortRules.parse(
            mystrg.get_value("psiphon_socks_port", AppConfig.PSIPHON_SOCKS),
            mystrg.get_value("psiphon_http_port", AppConfig.PSIPHON_HTTP)
        ) ?: run {
            Log.w(TAG, "Invalid saved Psiphon proxy ports; using automatic allocation")
            PsiphonProxyPorts(0, 0)
        }
        config.put("LocalSocksProxyPort", ports.socks)
        config.put("LocalHttpProxyPort", ports.http)


        // Ensure required fields
        if (!config.has("PropagationChannelId")) {
            config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        }
        if (!config.has("SponsorId")) {
            config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        }

        // Enable diagnostic notices for debugging
        config.put("EmitDiagnosticNotices", true)
        config.put("EmitDiagnosticNetworkParameters", false)
        config.put("EmitBytesTransferred", true)
        config.put("EmitServerAlerts", true)

        // Alternate DNS servers (matches psiphon-android)
        config.put("DNSResolverAlternateServers", org.json.JSONArray().apply {
            put("1.1.1.1")
            put("1.0.0.1")
            put("8.8.8.8")
            put("8.8.4.4")
        })

        // Don't set a timeout - keep trying
        if (!config.has("EstablishTunnelTimeoutSeconds")) {
            config.put("EstablishTunnelTimeoutSeconds", 0)
        }

        // Use the app's private directory for data
        val dataDir = File(filesDir, "psiphon_data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        config.put("DataRootDirectory", dataDir.absolutePath)

        return config.toString()
    }

    /**
     * Called by Psiphon tunnel core to protect sockets from VPN routing loops.
     * This is essential - Psiphon's network connections must bypass the VPN.
     *
     * Matches psiphon-android behavior: throw RuntimeException on failure so
     * tunnel-core knows to abort this connection attempt and retry.
     */
    override fun bindToDevice(fileDescriptor: Long) {
        val result = protect(fileDescriptor.toInt())
        if (!result) {
            throw RuntimeException("VpnService.protect() failed for fd=$fileDescriptor")
        }
    }

    override fun onConnecting() {
        Log.i(TAG, "Psiphon: Connecting...")
    }

    /**
     * Called when Psiphon tunnel is fully connected (activation handshake succeeded).
     * VPN TUN is already established — now start tun2socks to route device traffic
     * through Psiphon's local SOCKS proxy.
     */
    override fun onConnected() {
        if (teardownStarted.get() || !startGate.isActive()) return
        Log.i(TAG, "Psiphon connected; waiting for SOCKS listener before routing")
        handleRouteAction(routeGate.onConnected())
    }

    private fun handleRouteAction(action: PsiphonRouteAction) {
        when (action) {
            PsiphonRouteAction.Wait -> Unit
            is PsiphonRouteAction.Start -> startTun2socksRouting(action.port)
            PsiphonRouteAction.RestartRequired -> {
                Log.e(TAG, "Psiphon SOCKS endpoint changed while routing; stopping stale VPN")
                Thread { stopAll() }.start()
            }
        }
    }

    override fun onExiting() {
        if (teardownStarted.get()) return
        Log.w(TAG, "Psiphon core exited; closing VPN routing")
        Thread { stopAll() }.start()
    }

    override fun onListeningSocksProxyPort(port: Int) {
        if (teardownStarted.get() || !startGate.isActive()) return
        Log.i(TAG, "Psiphon SOCKS proxy port: $port")
        psiphonSocksPort.set(port)
        handleRouteAction(routeGate.onSocksPort(port))
    }

    override fun onListeningHttpProxyPort(port: Int) {
        Log.i(TAG, "Psiphon HTTP proxy port: $port")
        psiphonHttpPort.set(port)
        psiphon_http_port.set(port)
    }

    override fun onUpstreamProxyError(message: String?) {
        Log.e(TAG, "Psiphon upstream proxy reported an error")
    }

    override fun onDiagnosticMessage(message: String?) {
        // Do not write upstream diagnostic payloads (which may contain private
        // network endpoints or credentials) to Android's system logcat.
    }

    override fun onStartedWaitingForNetworkConnectivity() {
        Log.i(TAG, "Psiphon: Waiting for network connectivity...")
    }

    override fun onStoppedWaitingForNetworkConnectivity() {
        Log.i(TAG, "Psiphon: Network connectivity restored")
    }

    override fun onClientRegion(region: String?) {
        Log.i(TAG, "Psiphon client region: $region")
    }

    override fun onConnectedServerRegion(region: String?) {
        Log.i(TAG, "Psiphon connected to server in: $region")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun getEmbeddedServerEntries(): String {
        return try {
            assets.open("server_entries.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "No embedded server entries found")
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }

}
