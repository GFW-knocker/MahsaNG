package com.v2ray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProcessOwnerTest {
    private data class Child(val label: String)

    @Test fun fdHandoffCanOnlyBePublishedByTheExactOwnedChild() {
        val owner = TunnelProcessOwner<Child>(maxRestarts = 2)
        val (generation, _) = owner.begin()
        val first = Child("same")
        val other = Child("same")
        assertTrue(owner.adopt(generation, first))
        assertTrue(owner.owns(generation, first))
        // Identity matters: two processes with the same attributes are not interchangeable.
        assertFalse(owner.owns(generation, other))
        assertTrue(owner.departed(generation, first))
        assertTrue(owner.adopt(generation, other))
        assertFalse(owner.owns(generation, first))
        assertTrue(owner.owns(generation, other))
    }

    @Test fun fdHandoffIsRejectedForStoppedOrReplacedGeneration() {
        val owner = TunnelProcessOwner<Any>()
        val (firstGeneration, _) = owner.begin()
        val first = Any()
        assertTrue(owner.adopt(firstGeneration, first))
        val (newGeneration, oldChild) = owner.begin()
        assertEquals(first, oldChild)
        assertFalse(owner.owns(firstGeneration, first))
        val second = Any()
        assertTrue(owner.adopt(newGeneration, second))
        assertTrue(owner.owns(newGeneration, second))
        assertEquals(second, owner.stop())
        assertFalse(owner.owns(newGeneration, second))
    }

    @Test fun previousWatcherCannotRestartAfterNewSessionBegins() {
        val owner = TunnelProcessOwner<Any>()
        val (firstId, prior) = owner.begin()
        assertEquals(null, prior)
        val first = Any()
        assertTrue(owner.adopt(firstId, first))
        val (secondId, replaced) = owner.begin()
        assertEquals(first, replaced)
        assertFalse(owner.departed(firstId, first))
        assertFalse(owner.isCurrent(firstId))
        val second = Any()
        assertTrue(owner.adopt(secondId, second))
        assertFalse(owner.departed(firstId, first))
        assertTrue(owner.isCurrent(secondId))
    }

    @Test fun stoppedSessionCannotAdoptOrRestart() {
        val owner = TunnelProcessOwner<Any>()
        val (token, _) = owner.begin()
        val p = Any()
        assertTrue(owner.adopt(token, p))
        assertEquals(p, owner.stop())
        assertFalse(owner.isCurrent(token))
        assertFalse(owner.departed(token, p))
        assertFalse(owner.adopt(token, Any()))
    }

    @Test fun atMostOneActiveChildCanBeAdoptedPerSession() {
        val owner = TunnelProcessOwner<Any>()
        val (id, _) = owner.begin()
        val first = Any()
        assertTrue(owner.adopt(id, first))
        assertFalse(owner.adopt(id, Any()))
        assertTrue(owner.departed(id, first))
        assertTrue(owner.adopt(id, Any()))
    }

    @Test fun onlyOwnedWatcherCanRestartSameSession() {
        val owner = TunnelProcessOwner<Any>()
        val (id, _) = owner.begin()
        val p = Any()
        assertTrue(owner.adopt(id, p))
        assertFalse(owner.departed(id, Any()))
        assertTrue(owner.departed(id, p))
        assertFalse(owner.departed(id, p))
    }

    @Test fun stoppingTwiceDoesNotReturnSameProcessTwice() {
        val owner = TunnelProcessOwner<Any>()
        val (id, _) = owner.begin()
        val p = Any()
        assertTrue(owner.adopt(id, p))
        assertEquals(p, owner.stop())
        assertEquals(null, owner.stop())
    }

    @Test fun repeatedExitsHaveBoundedAutoRestartBudget() {
        val owner = TunnelProcessOwner<Any>(maxRestarts = 2)
        val (id, _) = owner.begin()
        repeat(2) {
            val process = Any()
            assertTrue(owner.adopt(id, process))
            assertTrue(owner.departed(id, process))
        }
        val last = Any()
        assertTrue(owner.adopt(id, last))
        assertFalse(owner.departed(id, last))
    }
}
