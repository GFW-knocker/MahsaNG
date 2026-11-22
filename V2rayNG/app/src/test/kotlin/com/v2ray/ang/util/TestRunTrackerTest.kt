package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TestRunTrackerTest {
    @Test fun startingNewRunRejectsLatePreviousCallbacks() {
        val tracker = TestRunTracker()
        val first = tracker.begin(listOf("a"))
        val second = tracker.begin(listOf("a"))
        assertFalse(tracker.accepts(first, "a"))
        assertTrue(tracker.accepts(second, "a"))
    }

    @Test fun aRunRejectsCallbacksForProfilesOutsideItsScope() {
        val tracker = TestRunTracker()
        val runId = tracker.begin(listOf("visible"))
        assertTrue(tracker.accepts(runId, "visible"))
        assertFalse(tracker.accepts(runId, "hidden"))
    }

    @Test fun snapshotRemainsStableWhenVisibleListChanges() {
        val tracker = TestRunTracker()
        val visible = mutableListOf("original", "removed")
        val id = tracker.begin(visible)
        visible.clear()
        visible.add("new")
        assertTrue(tracker.accepts(id, "original"))
        assertFalse(tracker.accepts(id, "new"))
    }

    @Test fun editingOneGuidInvalidatesOnlyThatGuidInTheActiveRun() {
        val tracker = TestRunTracker()
        val run = tracker.begin(listOf("edited", "untouched"))
        TestRunTracker.forgetProfile("edited")
        assertFalse(tracker.accepts(run, "edited"))
        assertTrue(tracker.accepts(run, "untouched"))
    }

    @Test fun everyLiveRunRejectsTheEditedGuid() {
        val first = TestRunTracker()
        val second = TestRunTracker()
        val firstRun = first.begin(listOf("edited", "other"))
        val secondRun = second.begin(listOf("edited"))
        TestRunTracker.forgetProfile("edited")
        assertFalse(first.accepts(firstRun, "edited"))
        assertFalse(second.accepts(secondRun, "edited"))
        assertTrue(first.accepts(firstRun, "other"))
    }

    @Test fun freshMeasurementsRemainPossibleAfterAnEdit() {
        val tracker = TestRunTracker()
        val stale = tracker.begin(listOf("edited"))
        TestRunTracker.forgetProfile("edited")
        val fresh = tracker.begin(listOf("edited"))
        assertFalse(tracker.accepts(stale, "edited"))
        assertTrue(tracker.accepts(fresh, "edited"))
        tracker.invalidate()
    }

    @Test fun guardedPublishRejectsStaleAndForgottenProfileWrites() {
        val tracker = TestRunTracker()
        val run = tracker.begin(listOf("edited"))
        var writes = 0
        assertTrue(tracker.publishIfCurrent(run, "edited") { writes++; true })
        tracker.forgetGuid("edited")
        assertFalse(tracker.publishIfCurrent(run, "edited") { writes++; true })
        assertEquals(1, writes)
    }

    @Test fun forgetWaitsForTheFinalWriteToFinish() {
        val tracker = TestRunTracker()
        val run = tracker.begin(listOf("edited"))
        val enteredWrite = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val forgetStarted = CountDownLatch(1)
        val forgot = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val worker = Thread {
            published.set(tracker.publishIfCurrent(run, "edited") {
                enteredWrite.countDown()
                check(finishWrite.await(5, TimeUnit.SECONDS))
                true
            })
        }
        worker.start()
        assertTrue(enteredWrite.await(5, TimeUnit.SECONDS))
        val editor = Thread {
            forgetStarted.countDown()
            tracker.forgetGuid("edited")
            forgot.countDown()
        }
        editor.start()
        assertTrue(forgetStarted.await(5, TimeUnit.SECONDS))
        try {
            assertFalse("The edit must not overtake the guarded write",
                forgot.await(80, TimeUnit.MILLISECONDS))
        } finally {
            finishWrite.countDown()
            worker.join(5000)
            editor.join(5000)
        }
        assertTrue(published.get())
        assertEquals(0L, forgot.count)
        assertFalse(tracker.accepts(run, "edited"))
    }

    @Test fun cancellationRejectsAllRemainingResults() {
        val tracker = TestRunTracker()
        val id = tracker.begin(listOf("a"))
        tracker.invalidate()
        assertFalse(tracker.accepts(id, "a"))
    }
}
