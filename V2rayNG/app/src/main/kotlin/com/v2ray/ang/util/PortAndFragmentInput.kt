package com.v2ray.ang.util

/** Strict saved-configuration input validation independent of Android APIs. */
internal object PortAndFragmentInput {
    fun validPort(port: Int): Boolean = port in 1..65535

    fun requirePort(port: Int): Int {
        require(validPort(port)) { "Invalid endpoint port" }
        return port
    }

    /** Fragment lengths, intervals and packet counts are positive whole values/ranges. */
    fun validFragmentRange(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        if (!Regex("[0-9]{1,5}(-[0-9]{1,5})?").matches(value)) return false
        val bounds = value.split("-").map { it.toIntOrNull() ?: return false }
        return bounds.all { it in 1..65535 } && (bounds.size == 1 || bounds[0] <= bounds[1])
    }
}
