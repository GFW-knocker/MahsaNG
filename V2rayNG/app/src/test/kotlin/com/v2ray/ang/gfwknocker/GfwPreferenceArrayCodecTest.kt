package com.v2ray.ang.gfwknocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GfwPreferenceArrayCodecTest {
    @Test fun preservesOrderAndSeparators() {
        val input = arrayOf("one", "two:three", "line\nnext", "", "🇮🇷")
        assertArrayEquals(input, GfwPreferenceArrayCodec.decode(GfwPreferenceArrayCodec.encode(input)))
    }

    @Test fun rejectsCorruptedOrOversizedContent() {
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode(null))
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode("corrupt"))
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode("v1|9999999|"))
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode("v1|1|5:ab"))
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode("v1|1|-1:abc"))
    }

    @Test fun roundTripsEmptyListsAndUnicodeWithoutLoss() {
        assertArrayEquals(emptyArray<String>(), GfwPreferenceArrayCodec.decode(GfwPreferenceArrayCodec.encode(emptyArray())))
        assertEquals("آزمایش", GfwPreferenceArrayCodec.decode(GfwPreferenceArrayCodec.encode(arrayOf("آزمایش")))[0])
    }
}
