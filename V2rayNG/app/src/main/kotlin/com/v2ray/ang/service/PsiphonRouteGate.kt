package com.v2ray.ang.service

internal sealed class PsiphonRouteAction {
    object Wait : PsiphonRouteAction()
    object RestartRequired : PsiphonRouteAction()
    data class Start(val port: Int) : PsiphonRouteAction()
}

/** Accepts native callbacks in either order and prevents duplicate JNI routing. */
internal class PsiphonRouteGate {
    private var connected = false
    private var socksPort = 0
    private var routed = false
    private var stopped = false

    @Synchronized fun onConnected(): PsiphonRouteAction {
        if (stopped) return PsiphonRouteAction.Wait
        connected = true
        return routeIfReady()
    }

    @Synchronized fun onSocksPort(port: Int): PsiphonRouteAction {
        if (stopped || port !in 1..65535) return PsiphonRouteAction.Wait
        if (routed && port != socksPort) return PsiphonRouteAction.RestartRequired
        socksPort = port
        return routeIfReady()
    }

    @Synchronized fun stop() {
        stopped = true
        connected = false
        socksPort = 0
        routed = false
    }

    private fun routeIfReady(): PsiphonRouteAction {
        if (!connected || routed || socksPort !in 1..65535) return PsiphonRouteAction.Wait
        routed = true
        return PsiphonRouteAction.Start(socksPort)
    }
}
