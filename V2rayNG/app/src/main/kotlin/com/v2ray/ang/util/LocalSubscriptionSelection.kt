package com.v2ray.ang.util

import com.v2ray.ang.dto.SubscriptionItem

/** Local-only groups have an empty URL; do not reuse a remote feed with the same label. */
internal object LocalSubscriptionSelection {
    fun existingLocalId(
        name: String,
        subscriptions: Collection<Pair<String, SubscriptionItem>>,
    ): String? {
        val label = name.trim().takeIf { it.isNotEmpty() } ?: return null
        return subscriptions.firstOrNull { (id, item) ->
            id.isNotBlank() && item.remarks.trim() == label && item.url.isBlank()
        }?.first
    }
}
