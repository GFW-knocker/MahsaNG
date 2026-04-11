/*
 * Tun2SocksJniLoader: JNI wrapper for Psiphon's badvpn tun2socks with udpgw support.
 *
 * This loads libtun2socks_psiphon.so (Psiphon's customized badvpn tun2socks),
 * which is separate from MahsaNG's libtun2socks.so (used by V2RayVpnService).
 *
 * The native library uses RegisterNatives in JNI_OnLoad to bind to this class,
 * so the class MUST be in the ca.psiphon package with this exact name.
 */
package ca.psiphon;

import android.util.Log;

public class Tun2SocksJniLoader {
    private static final String TAG = "Tun2SocksJniLoader";

    static {
        try {
            System.loadLibrary("tun2socks_psiphon");
            Log.i(TAG, "Loaded libtun2socks_psiphon.so successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libtun2socks_psiphon.so", e);
            throw e;
        }
    }

    /**
     * Initialize the tun2socks logger to forward native log messages to Java.
     *
     * @param className  Fully qualified name of the Java class with the log method
     * @param methodName Name of the static void method(String) to receive log messages
     */
    public static void initializeLogger(String className, String methodName) {
        initTun2socksLogger(className, methodName);
    }

    private native static void initTun2socksLogger(String className, String logMethodName);

    /**
     * Start tun2socks with udpgw transparent DNS support.
     * This call blocks until terminateTun2Socks() is called from another thread.
     *
     * @param vpnInterfaceFileDescriptor TUN file descriptor (will be owned by tun2socks)
     * @param vpnInterfaceMTU            MTU of the TUN interface
     * @param vpnIpv4Address             IPv4 address of the tun2socks network interface (router/gateway)
     * @param vpnIpv4NetMask             Netmask for the tun2socks network interface
     * @param vpnIpv6Address             IPv6 address, or null to disable IPv6
     * @param socksServerAddress          SOCKS5 proxy address (host:port) for TCP traffic
     * @param udpgwServerAddress          udpgw server address (host:port) for UDP relay (typically 127.0.0.1:7300)
     * @param udpgwTransparentDNS        1 to enable transparent DNS forwarding through udpgw, 0 to disable
     */
    public native static void runTun2Socks(
            int vpnInterfaceFileDescriptor,
            int vpnInterfaceMTU,
            String vpnIpv4Address,
            String vpnIpv4NetMask,
            String vpnIpv6Address,
            String socksServerAddress,
            String udpgwServerAddress,
            int udpgwTransparentDNS);

    /**
     * Signal tun2socks to terminate. Can be called from any thread.
     * After calling this, runTun2Socks() will return on its thread.
     */
    public native static void terminateTun2Socks();
}
