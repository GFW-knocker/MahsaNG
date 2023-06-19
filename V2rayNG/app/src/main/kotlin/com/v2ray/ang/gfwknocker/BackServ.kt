package com.v2ray.ang.gfwknocker

class backServ {
    var DoH_service: DoH_over_Fragment
    var HTTPS_serv: HTTPS_Fragmentor
    var TLS_serv: TLS_Fragmentor

    // constructor
    init {

        // DoH service to be used inside code for DNS
        DoH_service = DoH_over_Fragment(
            "cloudflare",
            "203.13.32.183", 443,
            true, 87, 0.01
        )

        // HTTPS proxy listening on 127.0.0.1:4500
        HTTPS_serv = HTTPS_Fragmentor(
            "127.0.0.1", 4500,
            "", -1,
            DoH_service, true,
            87, 0.01
        )
        // TLS proxy listening on 127.0.0.1:2500
        TLS_serv = TLS_Fragmentor(
            "127.0.0.1", 2500,
            "203.13.32.183", 443, true,
            87, 0.01
        )

        DoH_service.query("instagram.com")
        DoH_service.query("youtube.com")
        DoH_service.query("youtu.be")
        DoH_service.query("fb.com")
        DoH_service.query("twitter.com")

    }

    fun start_HTTPS_service() {
        HTTPS_serv.start()
    }

    fun stop_HTTPS_service() {
        HTTPS_serv.safely_stop_server()
        DoH_service.safely_stop_DoH()
    }

    fun start_TLS_service() {
        TLS_serv.start()
    }

    fun stop_TLS_service() {
        TLS_serv.safely_stop_server()
    }

}
