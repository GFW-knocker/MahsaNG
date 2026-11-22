package com.v2ray.ang.service

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentorForwardingWorkersTest {
    private class Resource : Closeable {
        val closes = AtomicInteger(0)
        override fun close() { closes.incrementAndGet() }
    }

    private fun heldWorker(entered: CountDownLatch, release: CountDownLatch): Thread =
        Thread {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
        }

    @Test fun noWorkersOnlyCompleteAfterStopWasRequested() {
        val gate = FragmentorForwardingWorkers()
        assertFalse(gate.awaitExited(20))
        gate.stop()
        assertTrue(gate.awaitExited(20))
    }

    @Test fun stopClosesTrackedSocketsWithoutAssumingWorkerExited() {
        val gate = FragmentorForwardingWorkers()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = Resource()
        val thread = heldWorker(entered, release)
        assertTrue(gate.start(thread, socket))
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gate.stop()
            assertEquals(1, socket.closes.get())
            assertFalse(gate.awaitExited(30))
        } finally {
            release.countDown()
            thread.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun stopPreventsNewClientOrDownstreamWorkerStart() {
        val gate = FragmentorForwardingWorkers()
        gate.stop()
        val worker = Thread { throw AssertionError("Must never start") }
        val socket = Resource()
        assertFalse(gate.start(worker, socket))
        assertEquals(Thread.State.NEW, worker.state)
        // A rejected accepted socket is closed by the caller, not inferred
        // from a worker that never took ownership.
        assertEquals(0, socket.closes.get())
        assertTrue(gate.awaitExited(100))
    }

    @Test fun lateBackendSocketRegistrationAfterStopClosesItImmediately() {
        val gate = FragmentorForwardingWorkers()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = Resource()
        val worker = heldWorker(entered, release)
        assertTrue(gate.start(worker))
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gate.stop()
            assertFalse(gate.track(worker, socket))
            assertEquals(1, socket.closes.get())
            assertFalse(gate.awaitExited(30))
        } finally {
            release.countDown()
            worker.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun allForwardingWorkersIncludingDownstreamMustExit() {
        val gate = FragmentorForwardingWorkers()
        val upEntered = CountDownLatch(1)
        val downEntered = CountDownLatch(1)
        val upRelease = CountDownLatch(1)
        val downRelease = CountDownLatch(1)
        val upstream = heldWorker(upEntered, upRelease)
        val downstream = heldWorker(downEntered, downRelease)
        assertTrue(gate.start(upstream))
        assertTrue(gate.start(downstream))
        try {
            assertTrue(upEntered.await(1, TimeUnit.SECONDS))
            assertTrue(downEntered.await(1, TimeUnit.SECONDS))
            gate.stop()
            upRelease.countDown()
            upstream.join(1_000)
            assertFalse(upstream.isAlive)
            assertFalse(gate.awaitExited(30))
        } finally {
            downRelease.countDown()
            upRelease.countDown()
            upstream.join(1_000)
            downstream.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun stopClosesBackendThatWasRegisteredDuringActiveForwarding() {
        val gate = FragmentorForwardingWorkers()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = Resource()
        val worker = heldWorker(entered, release)
        assertTrue(gate.start(worker))
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(gate.track(worker, backend))
            gate.stop()
            assertEquals(1, backend.closes.get())
        } finally {
            release.countDown()
            worker.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun unrelatedThreadCannotAttachResourcesAsIfItWereOwned() {
        val gate = FragmentorForwardingWorkers()
        val socket = Resource()
        assertFalse(gate.track(Thread {}, socket))
        assertEquals(1, socket.closes.get())
        gate.stop()
        assertTrue(gate.awaitExited(100))
    }

    @Test fun repeatedStopDoesNotReenableStartsOrLoseWorkerEvidence() {
        val gate = FragmentorForwardingWorkers()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = heldWorker(entered, release)
        val socket = Resource()
        assertTrue(gate.start(worker, socket))
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gate.stop()
            gate.stop()
            assertFalse(gate.start(Thread {}))
            assertFalse(gate.awaitExited(30))
        } finally {
            release.countDown()
            worker.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun aWorkerWhichExitedBeforeStopCanBeReaped() {
        val gate = FragmentorForwardingWorkers()
        val worker = Thread {}
        assertTrue(gate.start(worker))
        worker.join(1_000)
        assertFalse(worker.isAlive)
        gate.stop()
        assertTrue(gate.awaitExited(100))
    }

    @Test fun interruptedWaitFailsClosedAndPreservesInterrupt() {
        val gate = FragmentorForwardingWorkers()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = heldWorker(entered, release)
        assertTrue(gate.start(worker))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        gate.stop()
        Thread.currentThread().interrupt()
        try {
            assertFalse(gate.awaitExited(100))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
            worker.join(1_000)
        }
        assertTrue(gate.awaitExited(100))
    }

    @Test fun invalidTimeoutNeverAcknowledgesForwarderExit() {
        val gate = FragmentorForwardingWorkers()
        gate.stop()
        assertFalse(gate.awaitExited(0))
        assertFalse(gate.awaitExited(-1))
        assertFalse(gate.awaitExited(Long.MAX_VALUE))
        assertTrue(gate.awaitExited(100))
    }

    @Test fun aWorkerCannotAcknowledgeItsOwnTermination() {
        val gate = FragmentorForwardingWorkers()
        val incorrectlyCompleted = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val mayCheck = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            mayCheck.await(1, TimeUnit.SECONDS)
            incorrectlyCompleted.set(gate.awaitExited(100))
        }
        assertTrue(gate.start(worker))
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gate.stop()
        } finally {
            mayCheck.countDown()
            worker.join(1_000)
        }
        assertFalse(incorrectlyCompleted.get())
        assertTrue(gate.awaitExited(100))
    }
}
