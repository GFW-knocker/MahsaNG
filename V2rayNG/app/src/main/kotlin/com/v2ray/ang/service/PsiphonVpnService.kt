package com.v2ray.ang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.IBinder
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
import com.v2ray.ang.gfwknocker.GFW_sutil
import com.v2ray.ang.gfwknocker.GFW_txt_crypt
import com.v2ray.ang.gfwknocker.my_preference_storage
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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


    private var psiphonTunnel: PsiphonTunnel? = null
    private var mInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
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

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    // =========================================================================
    // Android Service lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
    }

    override fun onRevoke() {
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground immediately — Android kills the process if
        // startForegroundService() was used and startForeground() isn't called quickly
        startForegroundNotification()

        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }

        // Start Psiphon tunnel (waits for V2Ray SOCKS port first)
        startPsiphonTunnelAsync()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    // =========================================================================
    // Psiphon tunnel management
    // =========================================================================

    private fun startPsiphonTunnelAsync() {
        Thread {
            try {

                if ( mystrg.get_value("sw_psiphon_after","")=="true") {
                    // Wait for V2Ray's SOCKS proxy to become reachable
                    val v2raySocksPort = Utils.parseInt(
                        settingsStorage?.decodeString(AppConfig.PREF_SOCKS_PORT),
                        AppConfig.PORT_SOCKS.toInt()
                    )
                    Log.i(TAG, "Waiting for V2Ray SOCKS proxy on port $v2raySocksPort...")

                    if (!waitForPort(v2raySocksPort)) {
                        Log.e(
                            TAG,
                            "V2Ray SOCKS port $v2raySocksPort not reachable after timeout, aborting"
                        )
                        stopAll()
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
                Log.i(TAG, "Establishing VPN TUN interface before starting Psiphon...")
                establishVpnInterface()

                Log.i(TAG, "V2Ray SOCKS proxy is reachable, starting Psiphon tunnel...")
                psiphonTunnel = PsiphonTunnel.newPsiphonTunnel(this)
                psiphonTunnel?.setClientPlatformAffixes("", "")
                psiphonTunnel?.setVpnMode(true)
                psiphonTunnel?.startTunneling(getEmbeddedServerEntries())
                Log.i(TAG, "Psiphon tunnel started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Psiphon tunnel: ${e.message}", e)
                stopAll()
            }
        }.start()
    }

    /**
     * Wait for a TCP port on localhost to become reachable.
     * Returns true if connected within timeout, false otherwise.
     */
    private fun waitForPort(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + V2RAY_SOCKS_PORT_CHECK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
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
        if ( mystrg.get_value("sw_psiphon_after","")=="true") {
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

        // Close old interface
        try {
            mInterface?.close()
        } catch (ignored: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        mInterface = builder.establish()
        if (mInterface == null) {
            throw Exception("VpnService.Builder.establish() returned null — VPN permission not granted?")
        }
        isRunning.set(true)
        isPsiphonRunning.set(true)
        mystrg.put_value("is_psiphon_running","y")
        Log.i(TAG, "VPN TUN interface established (tun2socks will start after Psiphon connects)")

    }

    /**
     * Start tun2socks to route VPN TUN traffic through Psiphon's local SOCKS proxy.
     * Called from onConnected() when the Psiphon SOCKS proxy port is known.
     */
    private fun startTun2socksRouting() {
        val port = psiphonSocksPort.get()
        if (port <= 0) {
            Log.e(TAG, "Psiphon SOCKS port not available yet, cannot start tun2socks")
            return
        }
        if (mInterface == null) {
            Log.e(TAG, "VPN TUN interface not established, cannot start tun2socks")
            return
        }

        startTun2socks(port)
        Log.i(TAG, "tun2socks routing to Psiphon SOCKS port $port")

        // Notify the UI that we're connected
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_SUCCESS, "Psiphon Connected!")
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
        val iface = mInterface ?: return

        val socksServerAddress = "127.0.0.1:$socksPort"
        val ipv6Address = if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            PRIVATE_VLAN6_ROUTER
        } else {
            null
        }

        // Dup the fd so we can restart tun2socks without losing the VPN interface
        val tunFd = iface.dup().detachFd()

        Log.i(TAG, "Starting Psiphon tun2socks (JNI): socks=$socksServerAddress, " +
                "udpgw=$UDPGW_SERVER_ADDRESS, transparentDNS=1, fd=$tunFd")

        tun2socksThread = Thread {
            try {
                Tun2SocksJniLoader.runTun2Socks(
                    tunFd,
                    VPN_MTU,
                    PRIVATE_VLAN4_ROUTER,        // netif ipaddr (gateway)
                    "255.255.255.0",             // netmask
                    ipv6Address,                 // null = IPv4 only
                    socksServerAddress,          // Psiphon's local SOCKS proxy
                    UDPGW_SERVER_ADDRESS,        // udpgw server (Psiphon tunnel-core runs this)
                    1                            // enable udpgw transparent DNS
                )
                Log.i(TAG, "tun2socks (JNI) returned")
            } catch (e: Exception) {
                Log.e(TAG, "tun2socks (JNI) error: ${e.message}", e)
            }
        }.apply {
            name = "PsiphonTun2Socks"
            isDaemon = true
            start()
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
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name_m2)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            //val manager = getSystemService(NotificationManager::class.java)
            //manager?.notify(NOTIFICATION_ID, notification)

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }catch (e: Exception) {
            Log.d(TAG, "psiphon notification builder error: $e")
        }
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    private fun stopAll(isForced: Boolean = true) {
        isRunning.set(false)
        isPsiphonRunning.set(false)
        mystrg.put_value("is_psiphon_running","n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
            }
        }

        // Stop tun2socks (JNI)
        try {
            Log.d(TAG, "tun2socks terminate")
            Tun2SocksJniLoader.terminateTun2Socks()
            tun2socksThread?.join(3000)
            tun2socksThread = null
        } catch (e: Exception) {
            Log.d(TAG, "tun2socks terminate error: $e")
        }

        // Stop Psiphon tunnel
        try {
            psiphonTunnel?.stop()
            psiphonTunnel = null
            Log.i(TAG, "Psiphon tunnel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Psiphon: ${e.message}", e)
        }

        // Notify the UI
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "")

        if (isForced) {
            stopSelf()
            try {
                mInterface?.close()
            } catch (ignored: Exception) {
            }
        }
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



        if ( mystrg.get_value("sw_psiphon_after","")=="true") {
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


        val Psocks_port_string = mystrg.get_value("psiphon_socks_port",AppConfig.PSIPHON_SOCKS)
        val Psocks_port = Utils.parseInt(Psocks_port_string)
        config.put("LocalSocksProxyPort",Psocks_port)

        val Phttp_port_string = mystrg.get_value("psiphon_http_port",AppConfig.PSIPHON_HTTP)
        val Phttp_port = Utils.parseInt(Phttp_port_string)
        config.put("LocalHttpProxyPort",Phttp_port)


        // Ensure required fields
        if (!config.has("PropagationChannelId")) {
            config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        }
        if (!config.has("SponsorId")) {
            config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        }

        // Enable diagnostic notices for debugging
        config.put("EmitDiagnosticNotices", true)
        config.put("EmitDiagnosticNetworkParameters", true)
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
        Log.i(TAG, "Psiphon: Connected! Starting tun2socks routing...")
        updateNotification("Psiphon tunnel connected")
        startTun2socksRouting()
    }

    override fun onExiting() {
        Log.i(TAG, "Psiphon: Exiting")
    }

    override fun onListeningSocksProxyPort(port: Int) {
        Log.i(TAG, "Psiphon SOCKS proxy port: $port")
        psiphonSocksPort.set(port)
    }

    override fun onListeningHttpProxyPort(port: Int) {
        Log.i(TAG, "Psiphon HTTP proxy port: $port")
        psiphonHttpPort.set(port)
        psiphon_http_port.set(port)
    }

    override fun onUpstreamProxyError(message: String?) {
        Log.e(TAG, "Psiphon upstream proxy error (V2Ray): $message")
    }

    override fun onDiagnosticMessage(message: String?) {
        Log.d(TAG, "Psiphon: $message")
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
