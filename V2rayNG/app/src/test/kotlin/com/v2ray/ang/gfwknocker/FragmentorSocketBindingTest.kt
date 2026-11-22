package com.v2ray.ang.gfwknocker

import org.junit.Test
import org.junit.Assert.assertTrue

class FragmentorSocketBindingTest {
    @Test fun listenerBindsToConfiguredLoopbackInsteadOfAllInterfaces() {
        val socket = FragmentorSocketBinding.bind("127.0.0.1", 0)
        try { assertTrue(socket.inetAddress.isLoopbackAddress); assertTrue(socket.localPort > 0) }
        finally { socket.close() }
    }

    @Test fun unopenedListenerCanBeClosedWithoutError() {
        FragmentorSocketBinding.closeQuietly(null)
        val socket = FragmentorSocketBinding.bind("127.0.0.1", 0)
        FragmentorSocketBinding.closeQuietly(socket)
        FragmentorSocketBinding.closeQuietly(socket)
        assertTrue(socket.isClosed)
    }
}
