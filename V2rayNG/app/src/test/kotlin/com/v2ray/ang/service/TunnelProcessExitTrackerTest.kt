package com.v2ray.ang.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, deterministic accounting; these objects are fake Process identities. */
class TunnelProcessExitTrackerTest {
    @Test fun completionRequiresAnExplicitStopEvenWhenNoProcessWasEverCreated() {
        val tracker = TunnelProcessExitTracker<Any>()
        assertFalse(tracker.awaitStopped(20L))
        assertTrue(tracker.requestStop().isEmpty())
        assertTrue(tracker.awaitStopped(20L))
        assertFalse(tracker.beginLaunch())
    }

    @Test fun aDestroyedButUnexitedChildDoesNotProveShutdown() {
        val tracker = TunnelProcessExitTracker<Any>()
        val child = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(child))
        assertEquals(listOf(child), tracker.requestStop())
        assertFalse(tracker.awaitStopped(30L))
        assertTrue(tracker.observedExit(child))
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun pendingProcessCreationPreventsFalseSuccessAfterStop() {
        val tracker = TunnelProcessExitTracker<Any>()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.requestStop().isEmpty())
        assertFalse(tracker.awaitStopped(30L))
        val lateChild = Any()
        assertFalse(tracker.registered(lateChild))
        assertFalse(tracker.awaitStopped(30L))
        assertTrue(tracker.observedExit(lateChild))
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun failedPendingLaunchCanCompleteOnlyOnceItIsAccountedFor() {
        val tracker = TunnelProcessExitTracker<Any>()
        assertTrue(tracker.beginLaunch())
        tracker.requestStop()
        assertFalse(tracker.awaitStopped(30L))
        tracker.launchFailed()
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun allChildrenMustBeObservedExitedNotJustTheLastActiveChild() {
        val tracker = TunnelProcessExitTracker<Any>()
        val first = Any()
        val second = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(first))
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(second))
        assertEquals(setOf(first, second), tracker.requestStop().toSet())
        assertTrue(tracker.observedExit(second))
        assertFalse(tracker.awaitStopped(30L))
        assertTrue(tracker.observedExit(first))
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun exitEventRequiresExactChildIdentityNotEqualProperties() {
        data class Child(val label: String)
        val tracker = TunnelProcessExitTracker<Child>()
        val owned = Child("same")
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(owned))
        tracker.requestStop()
        assertFalse(tracker.observedExit(Child("same")))
        assertFalse(tracker.awaitStopped(30L))
        assertTrue(tracker.observedExit(owned))
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun repeatedStopDoesNotReopenRegistrationOrLoseOutstandingChild() {
        val tracker = TunnelProcessExitTracker<Any>()
        val child = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(child))
        assertEquals(listOf(child), tracker.requestStop())
        assertEquals(listOf(child), tracker.requestStop())
        assertFalse(tracker.beginLaunch())
        assertFalse(tracker.awaitStopped(30L))
        assertTrue(tracker.observedExit(child))
        assertTrue(tracker.awaitStopped(30L))
    }

    @Test fun waitObservesAnotherThreadReportingRealExit() {
        val tracker = TunnelProcessExitTracker<Any>()
        val child = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(child))
        tracker.requestStop()
        val gate = CountDownLatch(1)
        val exited = Thread {
            gate.await()
            tracker.observedExit(child)
        }
        exited.start()
        try {
            gate.countDown()
            assertTrue(tracker.awaitStopped(1_000L))
        } finally {
            exited.join(1_000L)
        }
    }

    @Test fun interruptionFailsClosedWithoutClearingTheCallerInterrupt() {
        val tracker = TunnelProcessExitTracker<Any>()
        val child = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(child))
        tracker.requestStop()
        Thread.currentThread().interrupt()
        try {
            assertFalse(tracker.awaitStopped(200L))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            tracker.observedExit(child)
        }
    }

    @Test fun invalidAndUnboundedWaitsDoNotAuthorizeStop() {
        val tracker = TunnelProcessExitTracker<Any>()
        tracker.requestStop()
        assertFalse(tracker.awaitStopped(0L))
        assertFalse(tracker.awaitStopped(-1L))
        assertFalse(tracker.awaitStopped(Long.MAX_VALUE))
        assertTrue(tracker.awaitStopped(100L))
    }

    @Test fun resetForNewSessionReopensOnlyAfterAllChildrenAndLaunchesFinish() {
        val tracker = TunnelProcessExitTracker<Any>()
        val first = Any()
        assertTrue(tracker.beginLaunch())
        assertTrue(tracker.registered(first))
        tracker.requestStop()
        assertFalse(tracker.resetForNewSession())
        assertFalse(tracker.beginLaunch())
        assertTrue(tracker.observedExit(first))
        assertTrue(tracker.awaitStopped(50L))
        assertTrue(tracker.resetForNewSession())
        assertTrue(tracker.beginLaunch())
        val second = Any()
        assertTrue(tracker.registered(second))
        tracker.requestStop()
        assertFalse(tracker.resetForNewSession())
        assertTrue(tracker.observedExit(second))
        assertTrue(tracker.awaitStopped(50L))
    }

    @Test fun resetForNewSessionRejectsInFlightLaunch() {
        val tracker = TunnelProcessExitTracker<Any>()
        assertTrue(tracker.beginLaunch())
        tracker.requestStop()
        assertFalse(tracker.resetForNewSession())
        tracker.launchFailed()
        assertTrue(tracker.resetForNewSession())
    }
}
