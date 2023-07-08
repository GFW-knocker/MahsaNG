package com.v2ray.ang.gfwknocker

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

// only support server with DoH-JSON-format (google,cloudflare)
// need recent java to support TLSv1.3 (java 17.0+ is recommended)
class DoH_over_Fragment(
    doh_url1: String?,
    target_ip1: String?, target_port1: Int,
    isFragment1: Boolean, num_fragment1: Int, fragment_sleep1: Double ,
    offline_DNS1 : MutableMap<String,Any>
) {
    var DNS_cache = mutableMapOf<String,Any>()
    var fragment_proxy: Proxy
    var doh_url: String? = null
    var fragment_serv: HTTPS_Fragmentor

    init {
        doh_url = if (doh_url1 == null) {
            "https://8.8.8.8/resolve?name="
        } else if (doh_url1 == "google") {
            "https://dns.google/resolve?name="
        } else if (doh_url1 == "cloudflare") {
            "https://cloudflare-dns.com/dns-query?name="
        } else {
            doh_url1
        }

        if(offline_DNS1 != null){
            DNS_cache = offline_DNS1
        }


        // construct a Fragmentor service only for DoH
        fragment_serv = HTTPS_Fragmentor(
            "127.0.0.1", 0,
            target_ip1!!, target_port1,
            null, isFragment1,
            num_fragment1, fragment_sleep1
        )
        fragment_serv.start()
        val proxyIP = fragment_serv.get_listen_ip()
        val proxyPort = fragment_serv.get_listen_port()
        fragment_proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyIP, proxyPort)) // Proxy.NO_PROXY;
        println("Construct DoH with Fragment=$isFragment1 ($num_fragment1) ")
    }

    fun query(domain: String): String? {
        var IP: String? = null
        if( DNS_cache[domain]!=null ){
            IP = DNS_cache[domain] as String?
//            println("offline DNS: $domain ---> $IP")
            return IP
        }

        try {
            // System.setProperty("https.protocols", "TLSv1.3");  // force to use tlsv1.3
            // System.setProperty("javax.net.debug", "ssl");
            val url = doh_url + domain + "&type=A&ct=" + URLEncoder.encode(
                "application/dns-json",
                "UTF-8"
            )
            val connection = URL(url).openConnection(fragment_proxy) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/dns-json")
            // connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            // int responseCode = connection.getResponseCode();
            // System.out.println("Response code: " + responseCode);
            val bis = BufferedReader(InputStreamReader(connection.inputStream))
            var inputLine: String?
            val response = StringBuilder()
            while (true) {
                inputLine = bis.readLine()
                if(inputLine == null){
                    break
                }
                response.append(inputLine)
            }
            bis.close()


            // parsing json and return first answer
            val jsonObject = JSONObject(response.toString())
            // System.out.println("Response body: " + response.toString());
            val answer_list = jsonObject.getJSONArray("Answer")
            for (i in 0 until answer_list.length()) {
                val x = answer_list.getJSONObject(i)
                if (x.getInt("type") == 1) {
                    IP = x.getString("data")
//                    println("online DNS: $domain ---> $IP")
                    DNS_cache[domain] = IP
                    break
                }
            }
        } catch (e: Exception) {
//            println("cant resolve $domain")
            println("DoH ERR: " + e.message)
        }
        return IP
    }

    fun safely_stop_DoH() {
        fragment_serv.safely_stop_server()
    }




}