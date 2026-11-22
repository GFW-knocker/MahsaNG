package com.v2ray.ang.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.util.AppFetchRoutePolicy
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Resolve app-owned HTTP requests on the UI thread, before starting network I/O.
 * A disconnected status cannot silently become a direct request: the user must approve it.
 */
internal object AppFetchRouteChooser {
    fun choose(
        activity: BaseActivity,
        reportsConnected: () -> Boolean,
        onChosen: (AppFetchRoutePolicy.Route) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val port = Utils.getConfiguredHttpPortOrNull()
            val proxyListening = withContext(Dispatchers.IO) {
                port != null && isProxyListening(port)
            }
            val expected = reportsConnected() || proxyListening
            when (val route = AppFetchRoutePolicy.choose(
                proxyExpected = expected,
                configuredHttpPort = port,
                trigger = AppFetchRoutePolicy.Trigger.USER,
            )) {
                is AppFetchRoutePolicy.Route.ViaProxy -> onChosen(route)
                AppFetchRoutePolicy.Route.Blocked -> {
                    if (expected) {
                        Toast.makeText(activity, R.string.app_fetch_proxy_unavailable, Toast.LENGTH_LONG).show()
                    } else if (!activity.isFinishing && !activity.isDestroyed) {
                        askForDirectConsent(activity, reportsConnected, onChosen)
                    }
                }
                AppFetchRoutePolicy.Route.Direct -> error("Direct fetch without user approval")
            }
        }
    }

    private fun askForDirectConsent(
        activity: BaseActivity,
        reportsConnected: () -> Boolean,
        onChosen: (AppFetchRoutePolicy.Route) -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.app_fetch_direct_title)
            .setMessage(R.string.app_fetch_direct_warning)
            .setPositiveButton(R.string.app_fetch_allow_direct) { _, _ ->
                activity.lifecycleScope.launch {
                    // Re-evaluate after consent. If the proxy became available, use it.
                    val port = Utils.getConfiguredHttpPortOrNull()
                    val proxyListening = withContext(Dispatchers.IO) {
                        port != null && isProxyListening(port)
                    }
                    val route = AppFetchRoutePolicy.choose(
                        proxyExpected = reportsConnected() || proxyListening,
                        configuredHttpPort = port,
                        trigger = AppFetchRoutePolicy.Trigger.USER,
                        directConfirmed = true,
                    )
                    if (route == AppFetchRoutePolicy.Route.Blocked) {
                        Toast.makeText(activity, R.string.app_fetch_proxy_unavailable, Toast.LENGTH_LONG).show()
                    } else if (!activity.isFinishing && !activity.isDestroyed) {
                        onChosen(route)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isProxyListening(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    } catch (_: Exception) {
        false
    }
}
