package com.v2ray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FragmentorNumericSettingsTest {
    @Test fun acceptsValidSettingsAndTrimsWhitespace() {
        val valid = FragmentorNumericSettings.parse(" 90 ", " 0.007 ")
        assertNotNull(valid)
        assertEquals(90, valid!!.fragments)
        assertEquals(0.007, valid.delaySeconds, 0.000001)
    }

    @Test fun rejectsBlankOrNonNumericInputs() {
        assertNull(FragmentorNumericSettings.parse("", "0.007"))
        assertNull(FragmentorNumericSettings.parse("oops", "0.007"))
        assertNull(FragmentorNumericSettings.parse("90", ""))
        assertNull(FragmentorNumericSettings.parse("90", "abc"))
        assertNull(FragmentorNumericSettings.parse(null, "0.007"))
    }

    @Test fun rejectsOutOfRangeOrNonFiniteInputs() {
        for (count in listOf("-1", "0", "2049")) {
            assertNull(FragmentorNumericSettings.parse(count, "0.007"))
        }
        for (delay in listOf("-0.01", "1.01", "NaN", "Infinity", "-Infinity")) {
            assertNull(FragmentorNumericSettings.parse("90", delay))
        }
    }

    @Test fun acceptsDocumentedBoundaries() {
        assertNotNull(FragmentorNumericSettings.parse("1", "0"))
        assertNotNull(FragmentorNumericSettings.parse("2048", "1"))
    }

    @Test fun corruptOrOutOfBoundsStoredSpinnerIndicesAreClamped() {
        assertEquals(0, FragmentorNumericSettings.selectedIndex(null, 5))
        assertEquals(0, FragmentorNumericSettings.selectedIndex("bad", 5))
        assertEquals(0, FragmentorNumericSettings.selectedIndex("-3", 5))
        assertEquals(4, FragmentorNumericSettings.selectedIndex("99", 5))
        assertEquals(2, FragmentorNumericSettings.selectedIndex("2", 5))
        assertEquals(0, FragmentorNumericSettings.selectedIndex("2", 0))
    }
}
