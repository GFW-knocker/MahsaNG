package com.v2ray.ang.gfwknocker

//import android.util.Log
//import android.net.VpnService

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

const val myT_socket_timeout = 8000  // Set socket timeout to 8 seconds

class TLS_Fragmentor(
    var listen_ip: String,
    var listen_port: Int,
    var target_ip: String,
    var target_port: Int,
    var isFragment: Boolean,
    var num_fragment: Int,
    var fragment_sleep: Double // in second
) : Thread() {
    var ss: ServerSocket? = null
    var client_sock: Socket? = null
    var is_ready = false



    override fun run() {
        try {
            ss = ServerSocket(listen_port, 128) // up to 128 concurrent connection queued
            listen_port = ss!!.localPort
            println("TLS Listening at $listen_ip:$listen_port")
            println("target $target_ip:$target_port\r\nnum_fragment:$num_fragment\r\nfragment_sleep:$fragment_sleep\r\nuse_fragment:$isFragment")

            is_ready = true
            while (true) {
//                println("TLS waiting for input socket ...")
                client_sock = ss!!.accept()

                val up_thread = My_upstream(
                    client_sock,
                    target_ip,
                    target_port,
                    isFragment,
                    num_fragment,
                    fragment_sleep
                )
                up_thread.start()
            }
        } catch (e: Exception) {
//            println("TLS Server ERR: " + e.message)
            is_ready = false
        } finally {
//            println("TLS Server Stopped. Listening Finished.")
            is_ready = false
        }
//        println("TLS Server Thread Finished")
    } // run

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
//            println("err in get listen port: " + e.message)
        }
        return listen_port
    }

    fun safely_stop_server() {
        try {
            ss!!.close()
        } catch (e: Exception) {
//            println("Safe Stop ERR: " + e.message)
            return
        }
//        println("TLS server socket safely stopped")
    }
} //class Server




internal class My_upstream(
    var client_sock: Socket?,
    var target_ip: String,
    var target_port: Int,
    var isFragment1: Boolean,
    var num_fragment1: Int,
    var fragment_sleep1: Double ) : Thread() {

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
        buff = ByteArray(4096)
        first_flag = isFragment1
        num_fragment = num_fragment1
        fragment_sleep_milisec = (fragment_sleep1 * 1000).toLong()
    }

    override fun run() {
        try {
            backend_sock = Socket(target_ip, target_port)
            backend_sock!!.soTimeout = myT_socket_timeout
            backend_sock!!.tcpNoDelay = true
            ops = backend_sock!!.getOutputStream()

            val down_thread = My_downstream(backend_sock!!.getInputStream(), client_sock!!.getOutputStream())
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
            ips!!.close()
        } catch (e: Exception) {
//            println("up-stream Close ERR: " + e.message)
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
                //				println("TLS send from "+ j_pre + " to "+ j_next);
                ops!!.write(buff, j_pre, j_next - j_pre)
                ops!!.flush()
                sleep(fragment_sleep_milisec)
                j_pre = j_next
            }
//            println("TLS send from $j_pre to $L")
            ops!!.write(buff, j_pre, L - j_pre)
            ops!!.flush()
        } catch (e: Exception) {
//            println("err in fragment function: " + e.message)
            return
        }
    }

    companion object {
        fun pickKRandomInts(k: Int, N: Int): List<Int> {
            var k = k
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





internal class My_downstream(var ips: InputStream, var ops: OutputStream) : Thread() {
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
