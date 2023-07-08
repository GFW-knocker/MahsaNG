package com.v2ray.ang.gfwknocker

import android.app.Service
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.MessageUtil


class backServ(ctx1 : Context , svc1 : Service , target_ip : String , target_port : Int , nfrag : Int , sleepfrag : Double , is_youtube : Boolean ){
    private var DoH_service: DoH_over_Fragment? = null
    private var HTTPS_serv: HTTPS_Fragmentor? = null
    private var TLS_serv: TLS_Fragmentor? = null
    private var ctx = ctx1
    private var svc = svc1

    // constructor
    init {

        var mystrg = my_preference_storage(ctx);
        var isp = mystrg.get_value("isp","mci")


        TLS_serv = TLS_Fragmentor(
            "127.0.0.1", 2500,
            target_ip , target_port, true,
            nfrag, sleepfrag
        )



        // --------------------- HTTPS and DOH --------------------
        if( is_youtube ) {
            val url_remote_config = mystrg.get_value(
                "url_remote_config",
                ctx.getResources().getString(R.string.remote_config_url)
            )
            var cpr = config_pkg_reader(url_remote_config, "mahsa_config.json", ctx)
            cpr.fetch_json_config(true)  // fetch mahsa config from shared preference or local asset


            var offline_dns = cpr.get_offline_DNS(isp)
            var doh_url = cpr.get_doh_url(isp)
            var doh_ip = cpr.get_doh_ip(isp)
            var doh_CF_flag = cpr.get_doh_CF_flag(isp)

            if (doh_url.isEmpty()) {
                doh_url = "cloudflare"
            }

            if (doh_CF_flag) {
                doh_ip = target_ip
            }

            // DoH service to be used inside code for DNS
            DoH_service = DoH_over_Fragment(
                doh_url, doh_ip, 443,
                true, nfrag, sleepfrag,
                offline_dns
            )

            // HTTPS proxy listening on 127.0.0.1:4500
            HTTPS_serv = HTTPS_Fragmentor(
                "127.0.0.1", 4500,
                "", -1,
                DoH_service, true,
                nfrag, sleepfrag
            )
        }


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
        HTTPS_serv!!.start()
    }

    fun stop_HTTPS_service() {
        HTTPS_serv!!.safely_stop_server()
        DoH_service!!.safely_stop_DoH()
    }

    fun start_TLS_service() {
        TLS_serv!!.start()
    }

    fun stop_TLS_service() {
        TLS_serv!!.safely_stop_server()
    }

}

