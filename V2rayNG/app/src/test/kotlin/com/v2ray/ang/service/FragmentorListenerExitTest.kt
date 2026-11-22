package com.v2ray.ang.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentorListenerExitTest {
    @Test fun noListenerIsNotAnOutstandingWorker() {
        assertTrue(FragmentorListenerExit.allExitedWithin(100L, null))
    }

    @Test fun unstartedListenerHasNoLiveThreadToAwait() {
        assertTrue(FragmentorListenerExit.allExitedWithin(100L, Thread {}))
    }

    @Test fun alreadyExitedListenerIsAccepted() {
        val listener = Thread {}
        listener.start()
        listener.join(1000L)
        assertFalse(listener.isAlive)
        assertTrue(FragmentorListenerExit.allExitedWithin(100L, listener))
    }

    @Test fun liveListenerDoesNotBecomeCompletionJustBecauseJoinTimedOut() {
        val live = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val listener = Thread {
            entered.countDown()
            while (live.get()) Thread.yield()
        }
        listener.start()
        try {
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            assertFalse(FragmentorListenerExit.allExitedWithin(30L, listener))
        } finally {
            live.set(false)
            listener.join(1000L)
        }
        assertFalse(listener.isAlive)
    }

    @Test fun oneLiveListenerMakesCombinedOutcomeIncomplete() {
        val live = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val listener = Thread {
            entered.countDown()
            while (live.get()) Thread.yield()
        }
        listener.start()
        try {
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            assertFalse(FragmentorListenerExit.allExitedWithin(30L, Thread {}, listener))
        } finally {
            live.set(false)
            listener.join(1000L)
        }
    }

    @Test fun currentThreadCannotAcknowledgeItsOwnExit() {
        assertFalse(FragmentorListenerExit.allExitedWithin(100L, Thread.currentThread()))
    }

    @Test fun interruptedWaitFailsClosedAndPreservesInterrupt() {
        val live = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val listener = Thread {
            entered.countDown()
            while (live.get()) Thread.yield()
        }
        listener.start()
        try {
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()
            assertFalse(FragmentorListenerExit.allExitedWithin(100L, listener))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // clear only the test's intentional interrupt
            live.set(false)
            listener.join(1000L)
        }
    }

    @Test fun invalidOrUnboundedTimeoutCannotProveExit() {
        assertFalse(FragmentorListenerExit.allExitedWithin(0L, null))
        assertFalse(FragmentorListenerExit.allExitedWithin(-1L, null))
        assertFalse(FragmentorListenerExit.allExitedWithin(Long.MAX_VALUE, null))
    }
}
