package com.v2ray.ang.util

/** A single persisted measurement, read from one affiliation record. */
internal data class FailureObservation(
    val delayMillis: Long,
    val observationId: String?,
)

/**
 * Confirmation of one negative measurement and its exact serialized profile.
 * Kept only in memory: do not log or persist a second copy of credentials.
 */
internal data class FailedServerEvidence(
    val guid: String,
    val profileRevision: String,
    val observedDelay: Long,
    val observationId: String,
)

/** Select only measured failures from the current, explicitly visible scope. */
internal object FailedServerSelection {
    fun findFailed(
        visibleGuids: Collection<String>,
        storedGuids: Collection<String>,
        delayByGuid: (String) -> Long?,
    ): List<String> {
        val stored = storedGuids.toHashSet()
        return visibleGuids.distinct().filter { guid ->
            guid in stored && (delayByGuid(guid) ?: 0L) < 0L
        }
    }

    /**
     * Require a unique test-observation ID in addition to a negative delay and
     * the exact profile payload. Legacy results without an ID are not safe for
     * destructive confirmation until measured again.
     */
    fun snapshotFailures(
        visibleGuids: Collection<String>,
        storedGuids: Collection<String>,
        observationByGuid: (String) -> FailureObservation?,
        profileRevisionByGuid: (String) -> String?,
    ): List<FailedServerEvidence> {
        val indexed = storedGuids.toHashSet()
        return visibleGuids.distinct().mapNotNull { guid ->
            if (guid !in indexed) return@mapNotNull null
            val observation = observationByGuid(guid) ?: return@mapNotNull null
            if (observation.delayMillis >= 0L) return@mapNotNull null
            val observationId = observation.observationId?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val revision = profileRevisionByGuid(guid)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            FailedServerEvidence(guid, revision, observation.delayMillis, observationId)
        }
    }

    /**
     * Recheck one affiliation observation and profile payload under the writer
     * gate. Reusing a GUID and the exact same negative numerical delay in a new
     * run cannot reuse confirmation from an older observation.
     */
    fun reconfirmFailures(
        confirmed: Collection<FailedServerEvidence>,
        storedGuids: Collection<String>,
        observationByGuid: (String) -> FailureObservation?,
        profileRevisionByGuid: (String) -> String?,
    ): List<String> {
        val indexed = storedGuids.toHashSet()
        return confirmed.distinctBy { it.guid }.filter { snapshot ->
            val observedNow = if (snapshot.guid in indexed) {
                observationByGuid(snapshot.guid)
            } else null
            snapshot.guid in indexed &&
                snapshot.observedDelay < 0L &&
                observedNow?.delayMillis == snapshot.observedDelay &&
                observedNow.observationId == snapshot.observationId &&
                profileRevisionByGuid(snapshot.guid) == snapshot.profileRevision
        }.map { it.guid }
    }
}
