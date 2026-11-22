package com.v2ray.ang.util

/** Legacy MuxBean records can lack the newer UI-only status field. */
object MuxModeRestoration {
    fun restore(status: String?, enabled: Boolean?): String = when (status) {
        "ON", "OFF" -> status
        else -> when (enabled) {
            true -> "ON"
            false -> "OFF"
            null -> ""
        }
    }
}
