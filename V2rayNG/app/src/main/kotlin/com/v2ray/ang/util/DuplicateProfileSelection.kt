package com.v2ray.ang.util

/** Pure selection for previewing duplicate deletion within one visible profile scope. */
internal object DuplicateProfileSelection {
    fun <K : Any> findDuplicateGuids(
        visibleGuids: Collection<String>,
        storedGuids: Collection<String>,
        selectedGuid: String?,
        identityByGuid: (String) -> K?,
    ): List<String> {
        val stored = storedGuids.toHashSet()
        val visible = visibleGuids.toList()
        val seen = HashSet<String>()
        val groups = LinkedHashMap<K, MutableList<String>>()
        for (guid in visible) {
            if (guid.isBlank() || guid !in stored || !seen.add(guid)) continue
            val key = identityByGuid(guid) ?: continue
            groups.getOrPut(key) { mutableListOf() }.add(guid)
        }
        val removable = groups.values.flatMap { group ->
            if (group.size <= 1) emptyList()
            else {
                val survivor = selectedGuid?.takeIf { it in group } ?: group.first()
                group.filterNot { it == survivor }
            }
        }.toHashSet()
        return visible.distinct().filter { it in removable }
    }
}
