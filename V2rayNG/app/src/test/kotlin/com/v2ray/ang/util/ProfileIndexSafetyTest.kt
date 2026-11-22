package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class ProfileIndexSafetyTest {
    @Test fun missingIndexIsEmptyButCorruptOrExplicitNullIndexIsNotWritable() {
        assertEquals(emptyList<String>(), ProfileIndexSafety.decode(null) {
            error("No parser call for missing index")
        })
        assertNull(ProfileIndexSafety.decode("") { emptyList() })
        assertNull(ProfileIndexSafety.decode("null") { null })
        assertNull(ProfileIndexSafety.decode("{bad") { error("Invalid JSON") })
    }

    @Test fun nullBlankAndDuplicateGuidsRefuseAWriteInsteadOfDroppingEntries() {
        assertNull(ProfileIndexSafety.decode("[bad]") { listOf("a", "") })
        assertNull(ProfileIndexSafety.decode("[bad]") { listOf("a", "a") })
        @Suppress("UNCHECKED_CAST")
        val nullEntry = listOf("valid", null) as List<String>
        assertNull(ProfileIndexSafety.decode("[bad]") { nullEntry })
    }

    @Test fun validIndexPreservesOriginalOrderWithoutMutatingParserOutput() {
        val original = mutableListOf("b", "a", "hidden")
        val decoded = requireNotNull(ProfileIndexSafety.decode("[valid]") { original })
        assertEquals(original, decoded)
        decoded.remove("a")
        assertEquals(listOf("b", "a", "hidden"), original)
    }
    @Test fun canSelectOnlyCurrentlyIndexedNonblankGuids() {
        val index = listOf("active", "other")
        assertTrue(ProfileIndexSafety.canSelect(index, "active"))
        assertFalse(ProfileIndexSafety.canSelect(index, "deleted"))
        assertFalse(ProfileIndexSafety.canSelect(index, ""))
        assertFalse(ProfileIndexSafety.canSelect(null, "active"))
    }

}
