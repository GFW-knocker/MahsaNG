package com.v2ray.ang.dto

import com.v2ray.ang.util.SmartRealPingEvidence

/**
 * observationId identifies one accepted test-result write. Older affiliations
 * deserialize with null and must be retested before destructive failed removal.
 */
data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var observationId: String? = null,
    /** Typed native real-ping history, separate from the last TCP/real display delay. */
    var realPingEvidence: SmartRealPingEvidence? = null,
) {
    fun getTestDelayString(): String {
        if (testDelayMillis == 0L) {
            return ""
        }
        return testDelayMillis.toString() + "ms"
    }
}
