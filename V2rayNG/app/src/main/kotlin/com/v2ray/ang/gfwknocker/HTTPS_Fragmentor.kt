package com.v2ray.ang.gfwknocker

import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.regex.Pattern

const val myH_socket_timeout = 8000  // Set socket timeout to 8 seconds

class HTTPS_Fragmentor(
    var listen_ip: String, var listen_port: Int,
    var target_ip: String, var target_port: Int,
    var DoH_obj: DoH_over_Fragment?, var isFragment: Boolean,
    var num_fragment: Int, // in second
    var fragment_sleep: Double
) : Thread() {
    var ss: ServerSocket? = null
    var client_sock: Socket? = null
    var is_ready = false
    override fun run() {
        try {
            ss = ServerSocket(listen_port, 128) // up to 128 concurrent connection queued
            listen_port = ss!!.localPort
            println("HTTPS Listening at $listen_ip:$listen_port")
            is_ready = true
            while (true) {
//                println("HTTPS waiting for input socket ...")
                client_sock = ss!!.accept()
                val up_thread = My_upstream_H(
                    client_sock,
                    target_ip,
                    target_port,
                    DoH_obj,
                    isFragment,
                    num_fragment,
                    fragment_sleep
                )
                up_thread.start()
            }
        } catch (e: Exception) {
//            println("HTTPS Server ERR: " + e.message)
            is_ready = false
        } finally {
//            println("HTTPS Server Stopped. Listening Finished.")
            is_ready = false
        }
        println("HTTPS Server Thread Finished")
    } // run

    fun get_listen_ip(): String {
        return listen_ip
    }

    fun get_listen_port(): Int {
        // waiting a few millisec to service starts then returning ports
        try {
            for (i in 1..300) {
                if (is_ready == true) {
                    break
                } else {
//                    println((i * 10).toString() + " milli second waiting to start threaded service")
                    sleep(10) // sleep 10 milisec
                }
            }
        } catch (e: Exception) {
            println("err in get listen port: " + e.message)
        }
        return listen_port
    }

    fun safely_stop_server() {
        try {
            ss!!.close()
        } catch (e: Exception) {
            println("Safe Stop ERR: " + e.message)
            return
        }
        println("server socket safely stopped")
    }
} //class Server

internal class My_upstream_H(
    var client_sock: Socket?,
    var target_ip: String,
    var target_port: Int,
    var DoH_obj: DoH_over_Fragment?, isFragment1: Boolean,
    num_fragment1: Int, fragment_sleep1: Double
) : Thread() {
    var ips: InputStream
    var ops: OutputStream? = null
    var backend_sock: Socket? = null
    var buff: ByteArray
    var b = 0
    var first_flag: Boolean
    var num_fragment: Int
    var fragment_sleep_milisec: Long
    var first_time_sleep: Long = 100 // wait 100 millisecond for first packet to fully receive

    init {
        ips = client_sock!!.getInputStream()
        buff = ByteArray(8192)
        first_flag = isFragment1
        num_fragment = num_fragment1
        fragment_sleep_milisec = (fragment_sleep1 * 1000).toLong()
    }

    override fun run() {
        try {
            backend_sock = handle_client_request(client_sock)
            if (backend_sock == null) {
                client_sock!!.close()
                return
            }

            ops = backend_sock!!.getOutputStream()

            val down_thread =
                My_downstream_H(backend_sock!!.getInputStream(), client_sock!!.getOutputStream())
            down_thread.start()
//            println("up-stream started")
            sleep(first_time_sleep) // wait n millisec for first packet to fully receive
            while (true) {
                b = ips.read(buff)
                if(b == -1){
                    break
                }
                if (first_flag == true) {
                    first_flag = false
                    send_data_in_fragment()
                } else {
                    ops!!.write(buff, 0, b)
                    ops!!.flush()
                }
            }
        } catch (e: Exception) {
//            println("up-stream: " + e.message)
        } finally {
//            println("up-stream finished")
        }
        safely_close_socket(client_sock)
        safely_close_socket(backend_sock)
        try {
            ops!!.flush()
            ops!!.close()
            ips.close()
        } catch (e: Exception) {
//            println("up-stream Close ERR: " + e.message)
        }
    }

    fun handle_client_request(cli_sock: Socket?): Socket? {
        var remote_host: String?
        val remote_port: Int
        val backend_sock: Socket
        var response_data: String
        val cis: InputStream
        var cosw: OutputStreamWriter? = null
        return try {
            cis = cli_sock!!.getInputStream()
            cosw = OutputStreamWriter(cli_sock.getOutputStream())
            sleep(10) //wait 10 milisec to fully recieve packet from client
            b = cis.read(buff)
            val data = String(buff, 0, b)
            val request_lines =
                data.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val requestParts =
                request_lines[0].split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val rMethod = requestParts[0]
            val rhost = requestParts[1]
            if (rMethod == "CONNECT") {
                if ( (target_port > 0) && (target_ip!="")  ) {  // ignore client request and send traffic to specific ip:port
                    remote_host = target_ip
                    remote_port = target_port
                } else {   // extract actual client request to send traffic to it.
                    val hp = rhost.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    remote_host = hp[0]
                    remote_port = hp[1].toInt()
                }
            } else if (rMethod == "GET" || rMethod == "POST" || rMethod == "HEAD" || rMethod == "OPTIONS" || rMethod == "PUT" || rMethod == "DELETE" || rMethod == "PATCH" || rMethod == "TRACE") {
                val q_url = rhost.replace("http://", "https://")
//                println("redirect to HTTPS (302) $q_url")
                response_data =
                    "HTTP/1.1 302 Found\r\nLocation: $q_url\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                cosw.write(response_data)
                cosw.flush()
                cli_sock.close()
                return null
            } else {
//                println("Unknown method ERR 400 : $rMethod")
                response_data = "HTTP/1.1 400 Bad Request\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
                cosw.write(response_data)
                cosw.flush()
                cli_sock.close()
                return null
            }
            if ( (target_ip=="") && (DoH_obj != null) ) {
                if (!isValidIPAddress(remote_host)) {
//                    println("query DoH --> $remote_host")
                    remote_host = DoH_obj!!.query(remote_host)
                }
            }
//            println("$remote_host --> $remote_port")
            backend_sock = Socket(remote_host, remote_port)
            backend_sock.soTimeout = myH_socket_timeout
            backend_sock.tcpNoDelay = true
            response_data =
                "HTTP/1.1 200 Connection established\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
            cosw.write(response_data)
            cosw.flush()
            backend_sock
        } catch (e: Exception) {
//            println("handle client Req ERR 502: " + e.message)
            response_data =
                "HTTP/1.1 502 Bad Gateway (is IP filtered?)\r\nProxy-agent: MyProxy/1.0\r\n\r\n"
            try {
                cosw!!.write(response_data)
                cosw.flush()
                cli_sock!!.close()
            } catch (e2: Exception) {
//                println("handle client write 502 ERR: " + e2.message)
            }
            null
        }
    }

    fun safely_close_socket(sock: Socket?) {
        try {
            if (sock != null) {
                if (sock.isConnected || !sock.isClosed) {
                    sock.shutdownInput()
                    sock.shutdownOutput()
                    sock.close()
                }
            }
        } catch (e: Exception) {
//            println("socket Close ERR: " + e.message)
        }
    }

    fun send_data_in_fragment() {
        try {
            val L = b
            val indices = pickKRandomInts(num_fragment - 1, L)
            var j_pre = 0
            var j_next: Int
            for (i in indices.indices) {
                j_next = indices[i]
                //				System.out.println("HTTPS send from "+ j_pre + " to "+ j_next);
                ops!!.write(buff, j_pre, j_next - j_pre)
                ops!!.flush()
                sleep(fragment_sleep_milisec)
                j_pre = j_next
            }
//            println("HTTPS send from $j_pre to $L")
            ops!!.write(buff, j_pre, L - j_pre)
            ops!!.flush()
        } catch (e: Exception) {
//            println("err in fragment function: " + e.message)
            return
        }
    }

    companion object {
        fun isValidIPAddress(ip: String?): Boolean {
            if (ip == null) {
                return false
            }
            val zeroTo255 = "(\\d{1,2}|(0|1)\\" + "d{2}|2[0-4]\\d|25[0-5])"
            val regex = "$zeroTo255\\.$zeroTo255\\.$zeroTo255\\.$zeroTo255"
            val p = Pattern.compile(regex)
            val m = p.matcher(ip)
            return m.matches()
        }

        fun pickKRandomInts(k1: Int, N: Int): List<Int> {
            var k = k1
            if (k > N) {
                k = N - 1
            }
            val nums: MutableList<Int> = ArrayList()
            for (i in 1..N) {
                nums.add(i)
            }
            Collections.shuffle(nums)
            val result: MutableList<Int> = ArrayList()
            for (i in 0 until k) {
                result.add(nums[i])
            }
            Collections.sort(result)
            return result
        }
    }
} // class upstream

internal class My_downstream_H(var ips: InputStream, var ops: OutputStream) : Thread() {
    var buff: ByteArray
    var b = 0

    init {
        buff = ByteArray(4096)
    }

    override fun run() {
        try {
//            println("down-stream started")
            while (true) {
                b = ips.read(buff)
                if(b == -1){
                    break
                }
                ops.write(buff, 0, b)
                ops.flush()
            }
        } catch (e: Exception) {
//            println("down-stream: " + e.message)
        } finally {
//            println("down-stream finished")
        }
        try {
            ops.flush()
            ops.close()
            ips.close()
        } catch (e: Exception) {
//            println("stream Close ERR: " + e.message)
        }
    }
} // class downstream
