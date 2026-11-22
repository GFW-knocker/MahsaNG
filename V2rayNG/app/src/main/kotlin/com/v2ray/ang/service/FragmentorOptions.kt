package com.v2ray.ang.service

/** Values backed by the existing GFW settings screen; no new preference keys. */
internal data class FragmentorOptions(
    val targetHost: String,
    val targetPort: Int,
    val fragments: Int,
    val delaySeconds: Double,
) {
    companion object {
        fun fromSettings(
            profileHost: String?,
            profilePort: Int?,
            preference: (key: String, default: String) -> String,
        ): FragmentorOptions? {
            if (!preference("sw_frag", "false").equals("true", ignoreCase = true)) return null
            if (profilePort == null || profilePort !in 1..65535) return null
            val host = when {
                preference("ip_checkbox", "false").equals("true", ignoreCase = true) ->
                    preference("manual_ip", "")
                preference("cdn_checkbox", "false").equals("true", ignoreCase = true) ->
                    preference("cdn_ip", "")
                else -> profileHost.orEmpty()
            }.trim()
            val local = host.lowercase().removeSurrounding("[", "]")
            if (host.isBlank() || local == "localhost" || local == "0.0.0.0" ||
                local == "::" || local == "::1" || local.startsWith("127.")) return null
            val numeric = FragmentorNumericSettings.parse(
                preference("nfrag", "90"), preference("sleepfrag", "0.007")
            ) ?: return null
            return FragmentorOptions(host, profilePort, numeric.fragments, numeric.delaySeconds)
        }
    }
}
