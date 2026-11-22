package com.v2ray.ang.util

import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.dto.SubscriptionItem
import java.util.UUID

object MmkvManager {
    const val ID_MAIN = "MAIN"
    const val ID_SERVER_CONFIG = "SERVER_CONFIG"
    const val ID_SERVER_RAW = "SERVER_RAW"
    const val ID_SERVER_AFF = "SERVER_AFF"
    const val ID_SUB = "SUB"
    const val ID_SETTING = "SETTING"
    const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    const val KEY_ANG_CONFIGS = "ANG_CONFIGS"

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val serverStorage by lazy { MMKV.mmkvWithID(ID_SERVER_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }

    /**
     * A corrupt index must never be treated as an empty index by a writer.
     * Normal reads degrade to an empty list without crashing the UI; mutation
     * paths use the nullable checked reader and refuse to overwrite the index.
     */
    private fun readServerIndex(): MutableList<String>? {
        val storage = mainStorage ?: return null
        val raw = try {
            storage.decodeString(KEY_ANG_CONFIGS)
        } catch (_: Exception) {
            return null
        }
        return ProfileIndexSafety.decode(raw) { json ->
            Gson().fromJson(json, Array<String>::class.java)?.toList()
        }
    }

    fun decodeServerList(): MutableList<String> = readServerIndex() ?: mutableListOf()

    /** Bounded cross-process native MMKV lease shared by app and VPN processes. */
    private inline fun <T> withIndexLease(unavailable: T, action: () -> T): T {
        val main = mainStorage ?: return unavailable
        return ProfileIndexMutationGate.execute(
            unavailable = unavailable,
            tryAcquire = {
                // Retry briefly across momentary cross-process MMKV commits
                // without ever blocking indefinitely on a held or dead lock.
                var acquired = main.tryLock()
                var attempt = 1
                while (!acquired && attempt < 4) {
                    try {
                        Thread.sleep(15L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    acquired = main.tryLock()
                    attempt++
                }
                acquired
            },
            release = { main.unlock() },
            action = action,
        )
    }

    fun decodeServerConfig(guid: String): ServerConfig? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverStorage?.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return runCatching { Gson().fromJson(json, ServerConfig::class.java) }.getOrNull()
    }

    @Synchronized
    fun encodeServerConfig(guid: String, config: ServerConfig): String {
        return withIndexLease("") {
        // Resolve the authoritative index before touching a payload. An invalid
        // index must not become a new, empty profile collection on first import.
        val serverList = readServerIndex() ?: return ""
        val main = mainStorage ?: return ""
        val payloads = serverStorage ?: return ""
        val key = guid.ifBlank { Utils.getUuid() }
        val indexed = key in serverList
        val previousPayload = if (indexed) null else runCatching { payloads.decodeString(key) }.getOrNull()
        if (!payloads.encode(key, Gson().toJson(config))) return ""
        if (!indexed) {
            val updated = serverList.toMutableList().apply { add(0, key) }
            if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(updated))) {
                // Restore any existing unindexed payload if index publication fails.
                runCatching {
                    if (previousPayload == null) payloads.remove(key)
                    else payloads.encode(key, previousPayload)
                }
                return ""
            }
            if (main.decodeString(KEY_SELECTED_SERVER).isNullOrBlank()) {
                main.encode(KEY_SELECTED_SERVER, key)
            }
        }
        return key
    
        }}

    /** Selection must remain indexed under the same native lease. */
    @Synchronized
    fun selectExistingServer(guid: String): Boolean {
        return withIndexLease(false) {
            if (!ProfileIndexSafety.canSelect(readServerIndex(), guid)) return false
            val main = mainStorage ?: return false
            main.encode(KEY_SELECTED_SERVER, guid)
        }
    }

    @Synchronized
    fun removeServer(guid: String): Boolean {
        return withIndexLease(false) {
        if (guid.isBlank()) return false
        val main = mainStorage ?: return false
        val serverList = readServerIndex() ?: return false
        if (guid !in serverList) return false
        val updated = serverList.filterNot { it == guid }
        if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(updated))) return false
        if (main.decodeString(KEY_SELECTED_SERVER) == guid) {
            main.remove(KEY_SELECTED_SERVER)
        }
        serverStorage?.remove(guid)
        serverAffStorage?.remove(guid)
        true
        }}

    /**
     * Reorder by GUIDs from the currently visible list, not filtered UI positions.
     * Uses the authoritative index under the same-process writer lock.
     */
    @Synchronized
    fun swapServerPositions(firstGuid: String, secondGuid: String): Boolean {
        return withIndexLease(false) {
        val current = readServerIndex() ?: return false
        val reordered = ProfileIndexSwap.swap(current, firstGuid, secondGuid) ?: return false
        if (reordered == current) return true
        return mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(reordered)) == true
    
        }}

    /**
     * Stage new payloads first, atomically publish the serialized GUID index, and
     * only then retire the old subscription payloads. This is an in-process
     * critical section, not a claim of cross-process MMKV transactions.
     */
    @Synchronized
    fun replaceSubscriptionServers(
        subid: String,
        stagedProfiles: Map<String, ServerConfig>,
    ): Boolean {
        return withIndexLease(false) {
        if (subid.isBlank() || stagedProfiles.isEmpty()) return false
        val main = mainStorage ?: return false
        val payloads = serverStorage ?: return false
        // An update downloaded before subscription deletion must not resurrect it.
        if (subStorage?.decodeString(subid).isNullOrBlank()) return false

        return try {
            val oldIndex = readServerIndex() ?: return false
            val oldGuids = oldIndex.filter { guid ->
                runCatching { decodeServerConfig(guid) }.getOrNull()?.subscriptionId == subid
            }.toSet()
            // Never overwrite an existing profile payload by reusing a staged
            // GUID; rollback must not delete another profile on partial failure.
            if (stagedProfiles.keys.any { key -> serverStorage?.decodeString(key) != null }) return false
            val newIndex = SubscriptionReplacementPlan.mergedIndex(
                oldIndex, oldGuids, stagedProfiles.keys.toList()
            )

            if (stagedProfiles.values.any { config ->
                    val outbound = config.getProxyOutbound()
                    val address = outbound?.getServerAddress()
                    val port = outbound?.getServerPort()
                    config.subscriptionId != subid ||
                        address.isNullOrBlank() || port == null || port !in 1..65535
                }) return false

            val selectedGuid = main.decodeString(KEY_SELECTED_SERVER)?.takeIf { it.isNotBlank() }
            val selectedConfig = selectedGuid?.let { runCatching { decodeServerConfig(it) }.getOrNull() }
            val outboundEncoder = Gson()
            val selectedAfter = SubscriptionReplacementPlan.matchSelected(
                selectedGuid, oldGuids, selectedConfig, stagedProfiles
            ) { before, after ->
                // Matching host:port alone can silently select another account.
                // Compare the complete outbound, including credentials, protocol,
                // TLS/transport and chain settings, before carrying the selection.
                SubscriptionReplacementPlan.matchesOutbound(
                    SubscriptionOutboundIdentity.serialize(before, outboundEncoder),
                    SubscriptionOutboundIdentity.serialize(after, outboundEncoder),
                )
            } ?: if (selectedGuid == null) stagedProfiles.keys.firstOrNull() else null

            val written = mutableListOf<String>()
            try {
                for ((guid, config) in stagedProfiles) {
                    // Record the key before writing: an exception after a partial
                    // write must not leave it looking like a committed profile.
                    written.add(guid)
                    if (!payloads.encode(guid, Gson().toJson(config))) {
                        error("Failed to stage subscription profile")
                    }
                }
                if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(newIndex))) {
                    error("Failed to publish subscription index")
                }
            } catch (_: Exception) {
                written.forEach { guid -> runCatching { payloads.remove(guid) } }
                return false
            }

            // A published replacement must never retain an old selected GUID.
            if (selectedAfter != selectedGuid) {
                runCatching {
                    if (selectedAfter == null || !main.encode(KEY_SELECTED_SERVER, selectedAfter)) {
                        main.remove(KEY_SELECTED_SERVER)
                    }
                }
            }
            // Leaving old, unindexed payloads after interruption is recoverable;
            // removing the old index before publication would lose user profiles.
            oldGuids.forEach { guid ->
                runCatching { payloads.remove(guid) }
                runCatching { serverAffStorage?.remove(guid) }
            }
            true
        } catch (_: Exception) {
            false
        }
    
        }}

    /** Remove one subscription with a single index update under the writer gate. */
    @Synchronized
    fun removeServerViaSubid(subid: String): Boolean =
        withIndexLease(false) { removeServerViaSubidLocked(subid) }

    private fun removeServerViaSubidLocked(subid: String): Boolean {
        if (subid.isBlank()) return false
        val indexed = readServerIndex() ?: return false
        val payloads = serverStorage ?: return false
        val main = mainStorage ?: return false
        val removed = payloads.allKeys()?.mapNotNull { guid ->
            val config = runCatching { decodeServerConfig(guid) }.getOrNull()
            guid.takeIf { config?.subscriptionId == subid }
        }?.toSet().orEmpty()
        if (removed.isEmpty()) return true
        val survivors = indexed.filterNot { it in removed }
        if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(survivors))) return false
        if (main.decodeString(KEY_SELECTED_SERVER) in removed) {
            main.remove(KEY_SELECTED_SERVER)
        }
        removed.forEach { guid ->
            payloads.remove(guid)
            serverAffStorage?.remove(guid)
        }
        return true
    }

    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage?.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return runCatching { Gson().fromJson(json, ServerAffiliationInfo::class.java) }.getOrNull()
    }

    @Synchronized
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        aff.observationId = UUID.randomUUID().toString()
        serverAffStorage?.encode(guid, Gson().toJson(aff))
    }

    /**
     * Persist a single native outbound HTTP-probe result only if its originating
     * profile is still indexed and byte-equivalent after the test service ran.
     * TCP reachability tests keep using encodeServerTestDelayMillis above.
     */
    @Synchronized
    fun encodeServerRealPingEvidence(
        guid: String,
        delayMillis: Long,
        expectedRevision: String,
        runId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (guid.isBlank() || expectedRevision.isBlank() || runId.isBlank() ||
            readServerIndex()?.contains(guid) != true
        ) return false
        val actualRevision = currentSmartProfileRevision(guid) ?: return false
        if (actualRevision != expectedRevision) return false

        val affiliation = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        if (affiliation.realPingEvidence?.profileRevision == expectedRevision &&
            affiliation.realPingEvidence?.latestRunId == runId
        ) return false
        val evidence = SmartConnectionRanking.record(
            affiliation.realPingEvidence, actualRevision, runId, delayMillis, nowMillis,
        ) ?: return false
        affiliation.realPingEvidence = evidence
        // Preserve the existing displayed last-test behavior, without making TCP
        // observations appear eligible to the smart recommender.
        affiliation.testDelayMillis = delayMillis
        affiliation.observationId = UUID.randomUUID().toString()
        return serverAffStorage?.encode(guid, Gson().toJson(affiliation)) == true
    }

    private fun currentSmartProfileRevision(guid: String): String? {
        val config = decodeServerConfig(guid) ?: return null
        return runCatching {
            ProfileRevisionDigest.fromSerializedConfig(Gson().toJson(config))
        }.getOrNull()
    }

    /** Preview one eligible recommendation from exactly the currently displayed GUIDs. */
    @Synchronized
    fun recommendSmartProfile(
        visibleGuids: Collection<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SmartRecommendation? {
        val indexed = readServerIndex()?.toHashSet() ?: return null
        val candidates = visibleGuids.distinct().mapNotNull { guid ->
            if (guid !in indexed) return@mapNotNull null
            val revision = currentSmartProfileRevision(guid) ?: return@mapNotNull null
            SmartConnectionRanking.Entry(
                guid, revision, decodeServerAffiliationInfo(guid)?.realPingEvidence,
            )
        }
        return SmartConnectionRanking.pick(
            candidates, mainStorage?.decodeString(KEY_SELECTED_SERVER), nowMillis,
        )
    }

    /** Revalidate the user-confirmed preview under the profile writer lock. */
    @Synchronized
    fun confirmSmartRecommendation(
        preview: SmartRecommendation,
        visibleGuids: Collection<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        return withIndexLease(false) {
        if (preview.guid !in visibleGuids || readServerIndex()?.contains(preview.guid) != true) {
            return false
        }
        val revision = currentSmartProfileRevision(preview.guid) ?: return false
        if (!SmartConnectionRanking.matchesPreview(
                preview, revision,
                decodeServerAffiliationInfo(preview.guid)?.realPingEvidence, nowMillis,
            )
        ) return false
        return mainStorage?.encode(KEY_SELECTED_SERVER, preview.guid) == true
    
        }}

    fun clearAllTestDelayResults() {
        serverAffStorage?.allKeys()?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                aff.testDelayMillis = 0
                aff.observationId = null
                aff.realPingEvidence = null
                serverAffStorage?.encode(key, Gson().toJson(aff))
            }
        }
    }

    /** Reset only the selected run's profiles; hidden subscriptions keep their test results. */
    @Synchronized
    fun clearTestDelayResults(visibleGuids: Collection<String>) {
        visibleGuids.distinct().forEach { guid ->
            runCatching { decodeServerAffiliationInfo(guid) }.getOrNull()?.let { affiliation ->
                affiliation.testDelayMillis = 0L
                affiliation.observationId = null
                serverAffStorage?.encode(guid, Gson().toJson(affiliation))
            }
        }
    }

    fun importUrlAsSubscription(url: String): Int {
        val subscriptions = decodeSubscriptions()
        subscriptions.forEach {
            if (it.second.url == url) {
                return 0
            }
        }
        val subItem = SubscriptionItem()
        subItem.remarks = "import sub"
        subItem.url = url
        subStorage?.encode(Utils.getUuid(), Gson().toJson(subItem))
        return 1
    }

    /**
     * Create or reuse a local-only group for the bundled GFW configuration.
     * Its URL is blank and auto-update is disabled: it is not a remote feed.
     * Never overwrite a real subscription that happens to have the same name.
     */
    @Synchronized
    fun import_Mahsa_Subscription(remark: String): String {
        val label = remark.trim().takeIf { it.isNotEmpty() } ?: return ""
        val existing = runCatching { decodeSubscriptions() }.getOrNull() ?: return ""
        LocalSubscriptionSelection.existingLocalId(label, existing)?.let { return it }
        val storage = subStorage ?: return ""
        val id = Utils.getUuid()
        val local = SubscriptionItem(remarks = label, url = "", enabled = false)
        return if (storage.encode(id, Gson().toJson(local))) id else ""
    }

    fun decodeSubscriptions(): List<Pair<String, SubscriptionItem>> {
        val storage = subStorage ?: return emptyList()
        // MMKV can contain one corrupt item after an interrupted import.
        // Snapshot keys once and isolate decoding failures per item.
        val keys = runCatching { storage.allKeys()?.toList().orEmpty() }.getOrDefault(emptyList())
        val gson = Gson()
        return SubscriptionIndexDecoder.read(
            keys = keys,
            readStoredJson = { key -> storage.decodeString(key) },
            parse = { raw -> gson.fromJson(raw, SubscriptionItem::class.java) },
        )
    }

    @Synchronized
    fun removeSubscription(subid: String) {
        withIndexLease(Unit) {
        // Do not orphan a subscription's profiles if index commit fails.
        if (removeServerViaSubidLocked(subid)) {
            subStorage?.remove(subid)
        }
    
        }}

    @Synchronized
    fun removeAllServer(): Boolean {
        return withIndexLease(false) {
            mainStorage?.clearAll()
            serverStorage?.clearAll()
            serverAffStorage?.clearAll()
            true
        }
    }

    /**
     * Preview only confirmed negative results from the currently displayed GUIDs.
     * Retain the raw stored profile revision in memory to avoid deleting a
     * modified profile that happens to keep the same GUID.
     */
    @Synchronized
    internal fun findConfirmedFailedServerCandidates(visibleGuids: Collection<String>): List<FailedServerEvidence> {
        val currentGuids = readServerIndex() ?: return emptyList()
        return FailedServerSelection.snapshotFailures(
            visibleGuids,
            currentGuids,
            { guid -> runCatching {
                decodeServerAffiliationInfo(guid)?.let { FailureObservation(it.testDelayMillis, it.observationId) }
            }.getOrNull() },
            { guid -> serverStorage?.decodeString(guid) },
        )
    }

    /**
     * Remove only the same observed failures and revisions shown in the user's
     * confirmation. Do not expand the candidate set after confirmation.
     * Publish the serialized index only once; preserve profiles if writing fails.
     */
    @Synchronized
    internal fun removeConfirmedFailedServers(confirmed: Collection<FailedServerEvidence>): Int {
        return withIndexLease(0) {
        if (confirmed.isEmpty()) return 0
        val main = mainStorage ?: return 0
        val currentGuids = readServerIndex() ?: return 0
        val failedGuids = FailedServerSelection.reconfirmFailures(
            confirmed, currentGuids,
            { guid -> runCatching {
                decodeServerAffiliationInfo(guid)?.let { FailureObservation(it.testDelayMillis, it.observationId) }
            }.getOrNull() },
            { guid -> serverStorage?.decodeString(guid) },
        )
        if (failedGuids.isEmpty()) return 0

        val failedSet = failedGuids.toHashSet()
        val survivingGuids = currentGuids.filterNot { it in failedSet }
        if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(survivingGuids))) return 0

        if (main.decodeString(KEY_SELECTED_SERVER) in failedSet) {
            main.remove(KEY_SELECTED_SERVER)
        }
        failedGuids.forEach { guid ->
            serverStorage?.remove(guid)
            serverAffStorage?.remove(guid)
        }
        return failedGuids.size
    
        }}

    /**
     * Preview duplicates only among the currently displayed GUIDs. A selected
     * profile wins over an earlier duplicate and is never a deletion candidate.
     */
    @Synchronized
    fun findDuplicateServerGuids(visibleGuids: Collection<String>): List<String> {
        val stored = readServerIndex() ?: return emptyList()
        val selected = mainStorage?.decodeString(KEY_SELECTED_SERVER)
        return DuplicateProfileSelection.findDuplicateGuids(visibleGuids, stored, selected) { guid ->
            runCatching { decodeServerConfig(guid)?.getProxyOutbound() }.getOrNull()
        }
    }

    /**
     * Recompute the preview under the shared writer lock: a changed selection,
     * endpoint or subscription must not extend the confirmed destructive action.
     * Publish a single updated GUID index before retiring the payloads.
     */
    @Synchronized
    fun removeDuplicateServers(
        confirmedCandidates: Collection<String>,
        visibleGuids: Collection<String>,
    ): Int {
        return withIndexLease(0) {
        val allowed = confirmedCandidates.toSet()
        if (allowed.isEmpty()) return 0
        val main = mainStorage ?: return 0
        val stored = readServerIndex() ?: return 0
        val selected = main.decodeString(KEY_SELECTED_SERVER)
        val removable = DuplicateProfileSelection.findDuplicateGuids(visibleGuids, stored, selected) { guid ->
            runCatching { decodeServerConfig(guid)?.getProxyOutbound() }.getOrNull()
        }.filter { it in allowed }
        if (removable.isEmpty()) return 0
        val toRemove = removable.toHashSet()
        val surviving = stored.filterNot { it in toRemove }
        if (!main.encode(KEY_ANG_CONFIGS, Gson().toJson(surviving))) return 0
        removable.forEach { guid ->
            serverStorage?.remove(guid)
            serverAffStorage?.remove(guid)
        }
        return removable.size
    
        }}

    /**
     * Sort against one immutable result snapshot. A concurrent profile-index
     * modification aborts publishing rather than overwriting imported GUIDs.
     * This is same-process coordination; MMKV writes are not cross-process transactions.
     */
    @Synchronized
    fun sortByTestResults(): Boolean {
        return withIndexLease(false) {
        val initial = readServerIndex()?.toList() ?: return false
        val ordered = ProfileDelayOrder.order(initial) { guid ->
            runCatching { decodeServerAffiliationInfo(guid)?.testDelayMillis }.getOrNull()
        }
        if (ordered == initial) return true
        val latest = readServerIndex() ?: return false
        if (latest != initial) return false
        return mainStorage?.encode(KEY_ANG_CONFIGS, Gson().toJson(ordered)) == true
    
        }}
}
