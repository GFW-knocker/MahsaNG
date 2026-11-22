package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class ProfileRevisionDigestTest {
    @Test fun sameProfileHasStableOpaqueHashAndCredentialEditInvalidatesIt() {
        val oldJson = """{"password":"s3cr3t-abc","host":"vpn.example.invalid"}"""
        val newJson = """{"password":"s3cr3t-def","host":"vpn.example.invalid"}"""
        val first = ProfileRevisionDigest.fromSerializedConfig(oldJson)
        assertEquals(first, ProfileRevisionDigest.fromSerializedConfig(oldJson))
        assertNotEquals(first, ProfileRevisionDigest.fromSerializedConfig(newJson))
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(first.contains("s3cr3t"))
        assertFalse(first.contains("vpn.example.invalid"))
    }

    @Test fun emptyConfigFailsClosedRatherThanReturningConstantRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileRevisionDigest.fromSerializedConfig("")
        }
    }
}
