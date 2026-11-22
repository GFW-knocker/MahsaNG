package com.v2ray.ang.util

import java.util.Locale

/** Certificate-verification bypass is opt-in and cannot be forced by imported profiles. */
internal object TlsCertificatePolicy {
    fun forImport(userAllowsInsecure: Boolean, legacyFlag: String? = null): Boolean {
        if (!userAllowsInsecure) return false
        return when (legacyFlag?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "true" -> true
            else -> false
        }
    }

    /** Turning the preference off also secures profiles saved by older versions. */
    fun forConnection(userAllowsInsecure: Boolean, savedAllowsInsecure: Boolean): Boolean =
        userAllowsInsecure && savedAllowsInsecure
}
