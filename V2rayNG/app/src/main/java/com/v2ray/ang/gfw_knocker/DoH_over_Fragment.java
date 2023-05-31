package com.v2ray.ang.gfw_knocker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import org.json.*;


// only support server with DoH-JSON-format (google,cloudflare)
// need recent java to support TLSv1.3 (java 17.0+ is recommended)
public class DoH_over_Fragment {


    Proxy fragment_proxy;
    String doh_url;
    HTTPS_Fragmentor fragment_serv;


    public DoH_over_Fragment(String doh_url1 , 
                            String target_ip1 , int target_port1 , 
                            boolean isFragment1 , int num_fragment1 , double fragment_sleep1 ){
        
        if(doh_url1==null){
            doh_url = "https://8.8.8.8/resolve?name=";
        }else if(doh_url1.equals("google")){
            doh_url = "https://8.8.8.8/resolve?name=";
        }else if(doh_url1.equals("cloudflare")){
            doh_url = "https://1.1.1.1/dns-query?name=";
        }else{
            doh_url = doh_url1;
        }
        

        // construct a Fragmentor service only for DoH
        fragment_serv = new HTTPS_Fragmentor("127.0.0.1" , 0 ,
                                            target_ip1 , target_port1 ,
                                            null , isFragment1 , 
                                            num_fragment1 , fragment_sleep1 );                                            
        fragment_serv.start();

        String proxyIP = fragment_serv.get_listen_ip();
        int proxyPort = fragment_serv.get_listen_port();
        fragment_proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyIP, proxyPort));   // Proxy.NO_PROXY;

        System.out.println("Construct DoH with Fragment="+isFragment1+" ("+num_fragment1+") ");
                
    }





    public String query(String domain) {
        String IP = null;
                
        try{
            // System.setProperty("https.protocols", "TLSv1.3");  // force to use tlsv1.3
            // System.setProperty("javax.net.debug", "ssl");

            String url = doh_url + domain + "&type=A&ct="+URLEncoder.encode("application/dns-json", "UTF-8");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(fragment_proxy);            
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/dns-json");
            // connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            // int responseCode = connection.getResponseCode();
            // System.out.println("Response code: " + responseCode);

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();


            // parsing json and return first answer
            JSONObject jsonObject = new JSONObject(response.toString());
            // System.out.println("Response body: " + response.toString());
            JSONArray answer_list = jsonObject.getJSONArray("Answer");
            for (int i=0;i<answer_list.length();i++){
                JSONObject x = answer_list.getJSONObject(i);  
                if(x.getInt("type")==1 ){                    
                    IP = x.getString("data");
                    System.out.println("online DNS: "+domain+" ---> "+IP);
                    break;
                }                            
            }
            
        
        }catch(Exception e){
            System.out.println("DoH ERR: "+e.getMessage());
        }

        return IP;        
    }



    public void safely_stop_DoH(){
        fragment_serv.safely_stop_server();
    }

}