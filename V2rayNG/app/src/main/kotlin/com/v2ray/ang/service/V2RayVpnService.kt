package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ERoutingMode
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.UnderlyingNetworkOwner
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "26.26.26.1"
        private const val PRIVATE_VLAN4_ROUTER = "26.26.26.2"
        private const val PRIVATE_VLAN6_CLIENT = "da26:2626::1"
        private const val PRIVATE_VLAN6_ROUTER = "da26:2626::2"
        private const val TUN2SOCKS = "libtun2socks.so"
    }

    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    private lateinit var mInterface: ParcelFileDescriptor

    private val tunProcesses = TunnelProcessOwner<Process>()
    private val tunExitTracker = TunnelProcessExitTracker<Process>()
    private val tunCloseTracker = TunDescriptorCloseTracker<ParcelFileDescriptor>()
    private val fdScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
    }

    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val owner = UnderlyingNetworkOwner<Network>()
    private val networkHandler = Handler(Looper.getMainLooper())
    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Binder callbacks can arrive late or out of order. Queue only a versioned
     * selection and revalidate on the main thread before updating the VPN.
     */
    private fun postUnderlyingNetworkUpdate(update: UnderlyingNetworkOwner.Update<Network>?) {
        if (update == null) return
        networkHandler.post {
            owner.publishIfCurrent(update) { network ->
                runCatching {
                    setUnderlyingNetworks(network?.let { arrayOf(it) })
                }.onFailure {
                    Log.w(packageName, "Could not publish underlying network")
                }
            }
        }
    }

    @Synchronized
    private fun registerUnderlyingNetworkCallback() {
        releaseUnderlyingNetworkCallback()
        val epoch = owner.begin()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                postUnderlyingNetworkUpdate(owner.onAvailable(epoch, network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Capabilities from an old upstream must not replace the new one.
                postUnderlyingNetworkUpdate(owner.onCapabilitiesChanged(epoch, network))
            }

            override fun onLost(network: Network) {
                postUnderlyingNetworkUpdate(owner.onLost(epoch, network))
            }
        }
        registeredNetworkCallback = callback
        try {
            connectivity.requestNetwork(defaultNetworkRequest, callback)
        } catch (_: Exception) {
            registeredNetworkCallback = null
            postUnderlyingNetworkUpdate(owner.stop())
            Log.w(packageName, "Unable to register underlying network observer")
        }
    }

    @Synchronized
    private fun releaseUnderlyingNetworkCallback() {
        val callback = registeredNetworkCallback
        registeredNetworkCallback = null
        // Invalidate queued notifications before unregistering the callback.
        postUnderlyingNetworkUpdate(owner.stop())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && callback != null) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        releaseUnderlyingNetworkCallback()
        stopOwnedTun2socks()
        fdScope.cancel()
        // Android may destroy the service without an explicit stop or revoke.
        // Always release the TUN, even when startup never completed.
        closeOwnedTunDescriptors()
        // CoreStopGate makes repeated shutdown idempotent. The manager checks
        // ownership and initiates stop/foreground cleanup under one monitor.
        V2RayServiceManager.stopV2rayPointIfOwner(this, cancelForeground = true)
        super.onDestroy()
    }

    private fun setup() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        // If the old interface has exactly the same parameters, use it!
        // Configure a builder while parsing the parameters.
        val builder = Builder()
        //val enableLocalDns = defaultDPreference.getPrefBoolean(AppConfig.PREF_LOCAL_DNS_ENABLED, false)

        val routingMode = settingsStorage?.decodeString(AppConfig.PREF_ROUTING_MODE) ?: ERoutingMode.GLOBAL_PROXY.value

        builder.setMtu(VPN_MTU)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        //builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        if (routingMode == ERoutingMode.BYPASS_LAN.value || routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value) {
            resources.getStringArray(R.array.bypass_private_ip_address).forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }


        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            if (routingMode == ERoutingMode.BYPASS_LAN.value || routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value) {
                builder.addRoute("2000::", 3) //currently only 1/8 of total ipV6 is in use
            } else {
                builder.addRoute("::", 0)
            }
        }

        if (settingsStorage?.decodeBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        } else {
            Utils.getVpnDnsServers()
                    .forEach {
                        if (Utils.isPureIpAddress(it)) {
                            builder.addDnsServer(it)
                        }
                    }
        }

        builder.setSession(V2RayServiceManager.currentConfig?.remarks.orEmpty())


        // -------------- GFW knocker --------------------------------
        if (settingsStorage?.decodeBool(AppConfig.PREF_PER_APP_PROXY) == true) {
            val bypassApps = settingsStorage?.decodeBool(AppConfig.PREF_BYPASS_APPS) ?: false
            if (bypassApps) {
                println("Disallow mode")
                builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
            } else {
                println("allow mode")
                builder.addAllowedApplication(BuildConfig.APPLICATION_ID)
            }
        } else{
            println("Disallow mode when per-app is off")
            builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
        }
        println("bypass -->" + BuildConfig.APPLICATION_ID)

        // -----------------------------------------------------------



        if (settingsStorage?.decodeBool(AppConfig.PREF_PER_APP_PROXY) == true) {
            val apps = settingsStorage?.decodeStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            val bypassApps = settingsStorage?.decodeBool(AppConfig.PREF_BYPASS_APPS) ?: false

            apps?.forEach {
                // ---- GFW knocker --------------
                println("bypass -->" + it)
                // -------------------------------
                try {
                    if (bypassApps)
                        builder.addDisallowedApplication(it)
                    else
                        builder.addAllowedApplication(it)
                } catch (e: PackageManager.NameNotFoundException) {
                    //Logger.d(e)
                }
            }
        }

        // A replaced TUN is still our responsibility until its own close
        // succeeds. Failed closure must never disappear behind a new handle.
        if (this::mInterface.isInitialized && !closeTrackedTunDescriptor(mInterface)) {
            Log.w(packageName, "Previous Xray TUN closure unverified")
            stopV2Ray()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerUnderlyingNetworkCallback()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (!fdScope.isActive) {
            throw IllegalStateException("Xray VPN service lifecycle is no longer active")
        }

        // Reserve BEFORE Builder.establish(), which can complete after Stop.
        if (!tunCloseTracker.beginEstablish()) {
            throw IllegalStateException("Xray VPN TUN establishment blocked by Stop")
        }
        var establishmentPending = true
        try {
            val established = builder.establish()
                ?: error("Unable to establish Xray VPN TUN")
            // Register and publish while holding the same monitor used by
            // requestStop. A late descriptor is tracked but never published.
            val started = synchronized(tunCloseTracker) {
                val allowed = tunCloseTracker.registered(established)
                establishmentPending = false
                if (allowed) {
                    mInterface = established
                    tunProcesses.begin()
                } else null
            }
            if (started == null) {
                closeTrackedTunDescriptor(established)
                return
            }
            val (generation, previousChild) = started
            previousChild?.destroy()
            runTun2socks(generation)
        } catch (e: Exception) {
            if (establishmentPending) tunCloseTracker.establishFailed()
            Log.e(packageName, "Xray VPN TUN establishment failed", e)
            // stopV2Ray requests stopSelf BEFORE closing any tracked PFD.
            stopV2Ray()
        }
    }

    private fun runTun2socks(generation: Long) {
        if (!tunProcesses.isCurrent(generation)) return
        val socksPort = Utils.parseInt(settingsStorage?.decodeString(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
        val cmd = arrayListOf(File(applicationContext.applicationInfo.nativeLibraryDir, TUN2SOCKS).absolutePath,
                "--netif-ipaddr", PRIVATE_VLAN4_ROUTER,
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "127.0.0.1:${socksPort}",
                "--tunmtu", VPN_MTU.toString(),
                "--sock-path", "sock_path",//File(applicationContext.filesDir, "sock_path").absolutePath,
                "--enable-udprelay",
                "--loglevel", "notice")

        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            cmd.add("--netif-ip6addr")
            cmd.add(PRIVATE_VLAN6_ROUTER)
        }
        if (settingsStorage?.decodeBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
            val localDnsPort = Utils.parseInt(settingsStorage?.decodeString(AppConfig.PREF_LOCAL_DNS_PORT), AppConfig.PORT_LOCAL_DNS.toInt())
            cmd.add("--dnsgw")
            cmd.add("127.0.0.1:${localDnsPort}")
        }
        Log.d(packageName, cmd.toString())

        // Account for a ProcessBuilder.start() that may finish after Stop.
        // Without this, a late unadopted child could outlive Stop Success.
        if (!tunExitTracker.beginLaunch()) return
        var launchUnaccounted = true
        try {
            val child = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(applicationContext.filesDir)
                .start()
            val registered = tunExitTracker.registered(child)
            launchUnaccounted = false
            val adopted = registered && tunProcesses.adopt(generation, child)

            // Always watch a successfully spawned child, even if Stop won
            // adoption. waitFor() is available on the app's minimum API.
            Thread({
                val exitCode = runCatching { child.waitFor() }.getOrNull()
                if (exitCode != null) {
                    tunExitTracker.observedExit(child)
                    if (adopted) {
                        if (tunProcesses.departed(generation, child)) {
                            Log.d(packageName, "$TUN2SOCKS exited ($exitCode), restarting owned session")
                            runTun2socks(generation)
                        } else if (tunProcesses.isCurrent(generation)) {
                            // A running VPN with no native bridge must stop.
                            Log.e(packageName, "$TUN2SOCKS restart budget exhausted")
                            stopV2Ray()
                        }
                    }
                }
            }, "tun2socks-watch").start()

            if (!adopted) {
                // The tracker retains this exact child until watcher exit.
                child.destroy()
                return
            }
            sendFd(generation, child)
        } catch (e: Exception) {
            if (launchUnaccounted) tunExitTracker.launchFailed()
            Log.e(packageName, "tun2socks start failed: $e")
            // A failed current-generation restart must not leave a live VPN without a bridge.
            if (tunProcesses.isCurrent(generation)) stopV2Ray()
        }
    }

    private fun sendFd(generation: Long, child: Process) {
        if (!tunProcesses.owns(generation, child)) return
        val fd = mInterface.fileDescriptor
        val path = File(applicationContext.filesDir, "sock_path").absolutePath

        fdScope.launch {
            for (tries in 0..5) {
                if (!isActive || !tunProcesses.owns(generation, child)) return@launch
                delay(50L shl tries)
                if (!isActive || !tunProcesses.owns(generation, child)) return@launch
                try {
                    LocalSocket().use { socket ->
                        socket.connect(
                            LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)
                        )
                        // Socket file paths can be reused by a restarted child.
                        if (!tunProcesses.owns(generation, child)) return@launch
                        socket.setFileDescriptorsForSend(arrayOf(fd))
                        socket.outputStream.write(42)
                    }
                    return@launch
                } catch (e: Exception) {
                    Log.d(packageName, "sendFd retry $tries failed: $e")
                }
            }

            // Without the FD, tun2socks cannot forward device packets. Do not
            // leave an apparently connected VPN that silently blackholes them.
            if (isActive && tunProcesses.owns(generation, child)) {
                Log.e(packageName, "tun2socks FD handoff exhausted; stopping VPN")
                withContext(Dispatchers.Main) {
                    if (tunProcesses.owns(generation, child)) stopV2Ray()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy Android O+ startForegroundService() contract before any early return.
        V2RayServiceManager.ensureForeground(this)
        V2RayServiceManager.startV2rayPoint()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    internal fun isLifecycleActive(): Boolean = fdScope.isActive

    /**
     * Reopen local TUN/subprocess trackers on a reused V2RayVpnService instance
     * ONLY while its fdScope is still active and after a verified clean stop left
     * zero pending establishments, unclosed descriptors, pending launches or live
     * tun2socks children.
     */
    internal fun prepareForVerifiedRestart(): Boolean {
        if (!fdScope.isActive) return false
        return tunCloseTracker.resetForNewSession() && tunExitTracker.resetForNewSession()
    }

    /** Signal termination on any lifecycle thread; do not block Android main. */
    private fun stopOwnedTun2socks() {
        val owned = tunProcesses.stop()
        // Keep every launched child (including late/unadopted children) on the
        // pending list until its own waitFor() watcher observes actual exit.
        val pending = tunExitTracker.requestStop()
        pending.forEach { child ->
            runCatching { child.destroy() }.onFailure {
                Log.w(packageName, "Unable to request tun2socks termination")
            }
        }
        if (owned != null && pending.none { it === owned }) {
            runCatching { owned.destroy() }.onFailure {
                Log.w(packageName, "Unable to request owned tun2socks termination")
            }
        }
    }

    /**
     * ONLY V2RayServiceManager's IO cleanup calls this bounded observer.
     * Not an authoritative native/TUN completion receipt for a VPN generation.
     */
    internal fun stopAndAwaitTun2socksExit(): Boolean {
        stopOwnedTun2socks()
        return tunExitTracker.awaitStopped(3_000L)
    }

    /**
     * Only a successfully returned close() for this exact descriptor removes
     * it from the pending set. The monitor prevents competing close calls.
     */
    private fun closeTrackedTunDescriptor(descriptor: ParcelFileDescriptor): Boolean =
        synchronized(tunCloseTracker) {
            if (!tunCloseTracker.isOutstanding(descriptor)) return@synchronized true
            val closed = runCatching { descriptor.close() }
                .onFailure { Log.w(packageName, "Xray VPN TUN descriptor close failed", it) }
                .isSuccess
            if (closed) tunCloseTracker.closeReturned(descriptor)
            closed
        }

    /** Request closure without a blocking wait on Android's lifecycle thread. */
    private fun closeOwnedTunDescriptors() {
        tunCloseTracker.requestStop().forEach { closeTrackedTunDescriptor(it) }
    }

    /**
     * Called only during the manager's IO shutdown. This verifies close()
     * returned for each owned PFD and no establishment remains in flight.
     * It cannot prove duplicated/native FD aliases were released.
     */
    internal fun stopAndAwaitTunDescriptorClose(): Boolean {
        // Preserve the existing stopSelf-before-TUN-close requirement.
        stopSelf()
        closeOwnedTunDescriptors()
        return tunCloseTracker.awaitClosed(3_000L)
    }

    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        stopOwnedTun2socks()
        releaseUnderlyingNetworkCallback()

        // Always release this instance's TUN/process resources, but atomically
        // refuse global shutdown when a new service has taken ownership.
        V2RayServiceManager.stopV2rayPointIfOwner(this)

        if (isForced) {
            // Preserve stopSelf-before-TUN-close ordering. Closing the
            // service-owned descriptor first previously left Xray's native
            // listener port occupied during rapid stop/start cycles.
            stopSelf()

            closeOwnedTunDescriptors()
        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setup()
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase,  Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }
}
