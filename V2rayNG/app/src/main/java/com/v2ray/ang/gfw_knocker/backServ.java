package com.v2ray.ang.gfw_knocker;

public class backServ {
    DoH_over_Fragment DoH_service;
    HTTPS_Fragmentor HTTPS_serv;
    TLS_Fragmentor TLS_serv;

    // constructor
    public backServ(){

        // DoH service to be used inside code for DNS
        DoH_service = new DoH_over_Fragment("google",
                "8.8.8.8", 443,
                true, 87, 0.02);


        // HTTPS proxy listening on 127.0.0.1:4500
        HTTPS_serv = new HTTPS_Fragmentor("127.0.0.1",4500,
                null, -1,
                DoH_service,true,
                87,0.02);

        TLS_serv = new TLS_Fragmentor("127.0.0.1",2500 ,
                "203.13.32.183",443,true,
                87,0.02 );

    }

    public void start_HTTPS_service(){
        HTTPS_serv.start();
    }

    public void stop_HTTPS_service(){
        HTTPS_serv.safely_stop_server();
        DoH_service.safely_stop_DoH();
    }


    public void start_TLS_service(){
        TLS_serv.start();
    }

    public void stop_TLS_service(){
        TLS_serv.safely_stop_server();
    }

}
