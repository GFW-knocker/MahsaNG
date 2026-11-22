package com.v2ray.ang.service

/**
 * Narrow, synchronous teardown observations for the Psiphon service.
 *
 * A successful JNI *termination request* is not evidence that a blocked
 * routing worker actually returned. Similarly, Android stopService() only
 * requests an upstream stop, and PsiphonTunnel.stop() returning is not a
 * cryptographic/native completion receipt.
 *
 * This is NOT a cross-process generation acknowledgment and must never be
 * written to VpnSessionRecordStore as proof of coordinated VPN shutdown.
 */
internal data class PsiphonTeardownEvidence(
    val workerRegistered: Boolean,
    val workerExited: Boolean,
    val jniTerminationSignalReturned: Boolean,
    val psiphonTunnelStopReturned: Boolean,
    val tunCloseReturned: Boolean,
    val dependsOnXray: Boolean,
    /** Independently verified completion, never inferred from stop() return. */
    val psiphonNativeStopAcknowledged: Boolean,
    val xrayNativeStopAcknowledged: Boolean,
) {
    /**
     * Report local service cleanup only once any registered worker was
     * actually observed terminated and local synchronous close calls returned.
     * The JNI signal by itself does not satisfy this check.
     */
    val localStopReportable: Boolean
        get() = (!workerRegistered || workerExited) &&
            psiphonTunnelStopReturned && tunCloseReturned

    /**
     * Deliberately separate from the local UI state: even a successful
     * stopService() request is not an Xray native stop acknowledgment.
     * No production caller currently supplies a verified upstream receipt.
     */
    val wholeChainCompletionEligible: Boolean
        get() = localStopReportable && psiphonNativeStopAcknowledged &&
            (!dependsOnXray || xrayNativeStopAcknowledged)
}
