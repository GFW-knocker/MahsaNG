package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class UnderlyingNetworkOwnerTest {
    @Test fun oldNetworkLossCannotEraseNewUnderlyingNetwork() {
        val owner = UnderlyingNetworkOwner<String>()
        val epoch = owner.begin()
        val wifi = requireNotNull(owner.onAvailable(epoch, "wifi"))
        val cellular = requireNotNull(owner.onAvailable(epoch, "cellular"))
        assertNull(owner.onLost(epoch, "wifi"))
        assertNull(owner.onCapabilitiesChanged(epoch, "wifi"))
        assertFalse(owner.isCurrent(wifi))
        assertTrue(owner.isCurrent(cellular))
        val published = mutableListOf<String?>()
        assertFalse(owner.publishIfCurrent(wifi) { published.add(it) })
        assertTrue(owner.publishIfCurrent(cellular) { published.add(it) })
        assertEquals(listOf("cellular"), published)
        val lost = requireNotNull(owner.onLost(epoch, "cellular"))
        assertTrue(owner.publishIfCurrent(lost) { published.add(it) })
        assertEquals(listOf("cellular", null), published)
    }

    @Test fun duplicateAvailableAndCapabilitiesDoNotFloodBinderUpdates() {
        val owner = UnderlyingNetworkOwner<String>()
        val epoch = owner.begin()
        assertNotNull(owner.onAvailable(epoch, "wifi"))
        assertNull(owner.onAvailable(epoch, "wifi"))
        assertNull(owner.onCapabilitiesChanged(epoch, "wifi"))
        assertNull(owner.onLost(epoch, "unrelated"))
    }

    @Test fun oldSessionCallbacksCannotResumeAfterStopAndRestart() {
        val owner = UnderlyingNetworkOwner<String>()
        val first = owner.begin()
        val stale = requireNotNull(owner.onAvailable(first, "wifi"))
        val stopped = owner.stop()
        assertFalse(owner.isCurrent(stale))
        assertNull(owner.onAvailable(first, "old-network"))
        assertTrue(owner.publishIfCurrent(stopped) { assertNull(it) })
        val second = owner.begin()
        assertNotEquals(first, second)
        assertNull(owner.onLost(first, "wifi"))
        assertFalse(owner.isCurrent(stopped))
        val fresh = requireNotNull(owner.onAvailable(second, "cellular"))
        assertTrue(owner.publishIfCurrent(fresh) { assertEquals("cellular", it) })
    }

    @Test fun delayedMainThreadApplyCannotPublishObsoleteNetwork() {
        val owner = UnderlyingNetworkOwner<String>()
        val epoch = owner.begin()
        val firstUpdate = requireNotNull(owner.onAvailable(epoch, "wifi"))
        val secondUpdate = requireNotNull(owner.onAvailable(epoch, "cellular"))
        val observed = mutableListOf<String?>()
        assertTrue(owner.publishIfCurrent(secondUpdate) { observed.add(it) })
        assertFalse(owner.publishIfCurrent(firstUpdate) { observed.add(it) })
        assertEquals(listOf("cellular"), observed)
    }
}
