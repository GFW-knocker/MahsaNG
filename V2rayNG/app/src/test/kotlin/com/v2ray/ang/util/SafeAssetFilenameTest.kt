package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeAssetFilenameTest {
    @Test fun keepsValidDocumentNamesWithoutInventingAPath() {
        assertEquals("geoip.dat", SafeAssetFilename.validate("geoip.dat"))
        assertEquals("geosite.dat", SafeAssetFilename.validate(" geosite.dat "))
        assertEquals("my-rules_v1.json", SafeAssetFilename.validate("my-rules_v1.json"))
    }

    @Test fun rejectsTraversalAbsolutePathsAndDirectorySeparators() {
        for (name in listOf("../secrets", "../../credentials", "/tmp/x", "foo/bar",
            "foo\\bar", ".", "..", "a/../b")) {
            assertNull("Accepted unsafe path: $name", SafeAssetFilename.validate(name))
        }
    }

    @Test fun rejectsUtf8NamesBeyondFilesystemByteLimit() {
        assertNull(SafeAssetFilename.validate("é".repeat(130)))
        assertEquals("é".repeat(100), SafeAssetFilename.validate("é".repeat(100)))
    }

    @Test fun rejectsMissingControlAndOversizeNames() {
        for (name in listOf(null, "", "  ", "a\u0000b", "line\nfeed", "x".repeat(256))) {
            assertNull("Accepted unsafe file name", SafeAssetFilename.validate(name))
        }
    }
}
