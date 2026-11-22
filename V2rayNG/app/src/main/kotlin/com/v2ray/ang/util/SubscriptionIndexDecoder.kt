package com.v2ray.ang.util

import com.v2ray.ang.dto.SubscriptionItem

/**
 * Decode each untrusted MMKV subscription record independently.
 * One damaged item must not hide healthy groups or crash the subscription UI.
 * Never log raw JSON or URLs: subscription credentials may be embedded.
 */
internal object SubscriptionIndexDecoder {
    fun read(
        keys: Collection<String>,
        readStoredJson: (String) -> String?,
        parse: (String) -> SubscriptionItem?,
    ): List<Pair<String, SubscriptionItem>> =
        keys.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { key ->
                val json = runCatching { readStoredJson(key) }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val item = runCatching { parse(json) }.getOrNull()
                    ?: return@mapNotNull null
                // Gson can assign JSON null to Kotlin non-null model properties.
                // Check both fields before exposing the item to Kotlin consumers.
                val requiredFieldsPresent = runCatching {
                    item.remarks.length
                    item.url.length
                    true
                }.getOrDefault(false)
                if (!requiredFieldsPresent) return@mapNotNull null
                key to item
            }
            .sortedWith(compareBy<Pair<String, SubscriptionItem>> { it.second.addedTime }
                .thenBy { it.first })
            .toList()
}
