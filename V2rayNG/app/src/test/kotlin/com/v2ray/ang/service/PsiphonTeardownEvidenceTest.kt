package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy UI Stop Success is not proof of full Xray/Psiphon chain teardown. */
class PsiphonTeardownEvidenceTest {
    private fun evidence(
        workerRegistered: Boolean = true,
        workerExited: Boolean = true,
        jniTerminationSignalReturned: Boolean = true,
        psiphonTunnelStopReturned: Boolean = true,
        tunCloseReturned: Boolean = true,
        dependsOnXray: Boolean = false,
        psiphonNativeStopAcknowledged: Boolean = false,
        xrayNativeStopAcknowledged: Boolean = false,
    ) = PsiphonTeardownEvidence(
        workerRegistered = workerRegistered,
        workerExited = workerExited,
        jniTerminationSignalReturned = jniTerminationSignalReturned,
        psiphonTunnelStopReturned = psiphonTunnelStopReturned,
        tunCloseReturned = tunCloseReturned,
        dependsOnXray = dependsOnXray,
        psiphonNativeStopAcknowledged = psiphonNativeStopAcknowledged,
        xrayNativeStopAcknowledged = xrayNativeStopAcknowledged,
    )

    @Test fun exitedWorkerAndSuccessfulLocalStopsAllowLegacyLocalReport() {
        assertTrue(evidence().localStopReportable)
        assertFalse(evidence().wholeChainCompletionEligible)
    }

    @Test fun absentWorkerNeedsNoTerminationSignal() {
        val result = evidence(
            workerRegistered = false,
            workerExited = false,
            jniTerminationSignalReturned = false,
        )
        assertTrue(result.localStopReportable)
    }

    @Test fun nativeTerminationSignalIsNotProofOfWorkerExit() {
        val result = evidence(workerExited = false, jniTerminationSignalReturned = true)
        assertFalse(result.localStopReportable)
        assertFalse(result.wholeChainCompletionEligible)
    }

    @Test fun timedOutWorkerCannotReportSuccessEvenIfTunnelAndTunCloseReturned() {
        val result = evidence(workerExited = false)
        assertFalse(result.localStopReportable)
    }

    @Test fun failedSignalWithObservedExitedWorkerIsStillObservedLocalCompletion() {
        val result = evidence(jniTerminationSignalReturned = false, workerExited = true)
        assertTrue(result.localStopReportable)
    }

    @Test fun failedTunnelStopNeverReportsLocalStopSuccess() {
        val result = evidence(psiphonTunnelStopReturned = false)
        assertFalse(result.localStopReportable)
        assertFalse(result.wholeChainCompletionEligible)
    }

    @Test fun failedTunCloseNeverReportsLocalStopSuccess() {
        val result = evidence(tunCloseReturned = false)
        assertFalse(result.localStopReportable)
        assertFalse(result.wholeChainCompletionEligible)
    }

    @Test fun psiphonNativeReceiptRequiredEvenWithoutUpstream() {
        assertFalse(evidence().wholeChainCompletionEligible)
        assertTrue(evidence(psiphonNativeStopAcknowledged = true).wholeChainCompletionEligible)
    }

    @Test fun upstreamStopRequestCannotImpersonateXrayNativeAcknowledgement() {
        val result = evidence(dependsOnXray = true, psiphonNativeStopAcknowledged = true, xrayNativeStopAcknowledged = false)
        assertTrue(result.localStopReportable)
        assertFalse(result.wholeChainCompletionEligible)
    }

    @Test fun onlyBothIndependentNativeAcknowledgementsEnableChainCompletion() {
        val result = evidence(dependsOnXray = true, psiphonNativeStopAcknowledged = true, xrayNativeStopAcknowledged = true)
        assertTrue(result.localStopReportable)
        assertTrue(result.wholeChainCompletionEligible)
    }

    @Test fun upstreamAcknowledgementCannotOverrideLocalNativeFailure() {
        val result = evidence(
            dependsOnXray = true,
            xrayNativeStopAcknowledged = true,
            psiphonNativeStopAcknowledged = true,
            workerExited = false,
        )
        assertFalse(result.localStopReportable)
        assertFalse(result.wholeChainCompletionEligible)
    }
}
