package com.v2ray.ang.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Compare saved profile revisions without retaining credential JSON in run tickets. */
internal object ProfileRevisionDigest {
    private const val HEX = "0123456789abcdef"

    fun fromSerializedConfig(serialized: String): String {
        require(serialized.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(StandardCharsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(hex)
    }
}
