package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class ProfileProbeFilterTest {
    @Test fun unknownFailureAndSuccessAreDistinctObservations() {
        assertTrue(ProfileProbeFilter.matches(null, ProfileProbeStatus.UNTESTED, null))
        assertTrue(ProfileProbeFilter.matches(0, ProfileProbeStatus.UNTESTED, null))
        assertFalse(ProfileProbeFilter.matches(0, ProfileProbeStatus.SUCCESS, null))
        assertTrue(ProfileProbeFilter.matches(65, ProfileProbeStatus.SUCCESS, null))
        assertTrue(ProfileProbeFilter.matches(-1, ProfileProbeStatus.FAILURE, null))
        assertFalse(ProfileProbeFilter.matches(-1, ProfileProbeStatus.UNTESTED, null))
    }

    @Test fun latencyCapIncludesOnlyMeasuredValuesAtOrBelowLimit() {
        assertTrue(ProfileProbeFilter.matches(65, ProfileProbeStatus.ALL, 100))
        assertFalse(ProfileProbeFilter.matches(105, ProfileProbeStatus.ALL, 100))
        assertFalse(ProfileProbeFilter.matches(-1, ProfileProbeStatus.ALL, 100))
        assertFalse(ProfileProbeFilter.matches(null, ProfileProbeStatus.ALL, 100))
        assertFalse(ProfileProbeFilter.matches(65, ProfileProbeStatus.SUCCESS, -1))
        assertFalse(ProfileProbeFilter.matches(65, ProfileProbeStatus.SUCCESS, 60_001))
    }
}
