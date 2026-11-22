package com.v2ray.ang.gfwknocker

import android.app.Service
import android.content.Context
import com.v2ray.ang.service.FragmentorListenerExit


class backServ(ctx1 : Context , svc1 : Service , target_ip : String , target_port : Int , nfrag : Int , sleepfrag : Double , is_youtube : Boolean ){
    private var DoH_service: DoH_over_Fragment? = null
    private var HTTPS_serv: HTTPS_Fragmentor? = null
    private var TLS_serv: TLS_Fragmentor? = null
    private var ctx = ctx1
    private var svc = svc1

    // constructor
    init {

        // The public source does not contain config_pkg_reader or its DNS rules.
        // Do not guess DoH targets or silently start a partially configured proxy.
        require(!is_youtube) { "YouTube/DoH mode has no published config parser" }

        TLS_serv = TLS_Fragmentor(
            "127.0.0.1", 2500,
            target_ip , target_port, true,
            nfrag, sleepfrag
        )



        // No optional DoH listener is constructed until its source and rules are restored.

//        DoH_service!!.query("instagram.com")
//        DoH_service!!.query("youtube.com")
//        DoH_service!!.query("youtu.be")
//        DoH_service!!.query("fb.com")
//        DoH_service!!.query("twitter.com")
//        DoH_service!!.query("google.com")
//        DoH_service!!.query("google.com")
//        DoH_service!!.query("fb.com")
//        DoH_service!!.query("google.com")

    }


    fun start_HTTPS_service() {
        // YouTube/DoH forwarding is optional; it is not created for normal profiles.
        HTTPS_serv?.let { if (it.state == Thread.State.NEW) it.start() }
    }

    fun stop_HTTPS_service() {
        runCatching { HTTPS_serv?.safely_stop_server() }
        runCatching { DoH_service?.safely_stop_DoH() }
    }

    fun start_TLS_service() {
        TLS_serv?.let { if (it.state == Thread.State.NEW) it.start() }
    }

    fun stop_TLS_service() {
        runCatching { TLS_serv?.safely_stop_server() }
    }

    /**
     * Stop optional listeners AND every accepted-client up/downstream worker.
     * Socket closes are requests, not worker-exit evidence. One bounded
     * deadline applies to all observed listener/forwarder Java Threads.
     * This does not certify native TUN, Xray or Psiphon resources.
     */
    fun stopAndAwaitListeners(timeoutMillis: Long = 3_000L): Boolean {
        // Always request both shutdown paths, even if either throws.
        val startedNs = System.nanoTime()
        val httpsStopReturned = runCatching { stop_HTTPS_service() }.isSuccess
        val tlsStopReturned = runCatching { stop_TLS_service() }.isSuccess
        if (timeoutMillis !in 1L..10_000L) return false

        fun remainingMillis(): Long {
            val elapsed = System.nanoTime() - startedNs
            val remainingNs = timeoutMillis * 1_000_000L - elapsed
            return if (remainingNs <= 0L) 0L else
                minOf(timeoutMillis, remainingNs / 1_000_000L + if (remainingNs % 1_000_000L == 0L) 0L else 1L)
        }

        val listenersExited = FragmentorListenerExit.allExitedWithin(
            remainingMillis(), HTTPS_serv, TLS_serv, DoH_service?.fragment_serv
        )
        // Do not short-circuit: still attempt to observe all worker groups.
        val httpsForwardersExited = HTTPS_serv?.awaitForwardersExited(remainingMillis()) ?: true
        val tlsForwardersExited = TLS_serv?.awaitForwardersExited(remainingMillis()) ?: true
        val dohForwardersExited = DoH_service?.fragment_serv?.awaitForwardersExited(remainingMillis()) ?: true
        val allExited = listenersExited && httpsForwardersExited && tlsForwardersExited &&
            dohForwardersExited
        return httpsStopReturned && tlsStopReturned && allExited
    }
}

