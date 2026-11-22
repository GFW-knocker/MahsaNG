package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsCertificatePolicyTest {
    @Test fun defaultPreferenceMustNeverDisableCertificateValidation() {
        assertFalse(TlsCertificatePolicy.forImport(userAllowsInsecure = false))
        assertFalse(TlsCertificatePolicy.forImport(userAllowsInsecure = false, legacyFlag = "true"))
    }

    @Test fun explicitUserOptInAllowsImportedTlsException() {
        assertTrue(TlsCertificatePolicy.forImport(userAllowsInsecure = true))
        assertTrue(TlsCertificatePolicy.forImport(userAllowsInsecure = true, legacyFlag = "true"))
    }

    @Test fun explicitLegacyFalseOverridesUserOptIn() {
        assertFalse(TlsCertificatePolicy.forImport(userAllowsInsecure = true, legacyFlag = "false"))
    }

    @Test fun malformedLegacyFlagFailsClosed() {
        assertFalse(TlsCertificatePolicy.forImport(userAllowsInsecure = true, legacyFlag = "garbage"))
    }

    @Test fun turningPreferenceOffDisablesPersistedInsecureFlagAtConnectionTime() {
        assertFalse(TlsCertificatePolicy.forConnection(userAllowsInsecure = false, savedAllowsInsecure = true))
    }

    @Test fun userOptInStillHonorsSavedProfileChoice() {
        assertTrue(TlsCertificatePolicy.forConnection(userAllowsInsecure = true, savedAllowsInsecure = true))
        assertFalse(TlsCertificatePolicy.forConnection(userAllowsInsecure = true, savedAllowsInsecure = false))
    }

    @Test fun blankLegacyFlagFollowsUserPreference() {
        assertTrue(TlsCertificatePolicy.forImport(userAllowsInsecure = true, legacyFlag = "   "))
        assertFalse(TlsCertificatePolicy.forImport(userAllowsInsecure = false, legacyFlag = "  "))
    }
}
