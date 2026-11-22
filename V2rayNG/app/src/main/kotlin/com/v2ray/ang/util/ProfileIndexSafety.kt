package com.v2ray.ang.util

/**
 * Validates the serialized profile index without silently repairing it.
 *
 * A missing key means an empty first-run index. Corrupt JSON, explicit null,
 * duplicate identifiers and null/blank keys must never authorize a write that
 * could overwrite the user's last usable profile index.
 */
internal object ProfileIndexSafety {
    /** Refuse to select a GUID already removed by another Android process. */
    fun canSelect(index: List<String>?, guid: String): Boolean =
        guid.isNotBlank() && index?.contains(guid) == true

    fun decode(raw: String?, parse: (String) -> List<String>?): MutableList<String>? {
        if (raw == null) return mutableListOf()
        if (raw.isBlank()) return null
        return runCatching {
            val keys = parse(raw) ?: return null
            val seen = HashSet<String>()
            if (keys.any { it.isBlank() || !seen.add(it) }) return null
            keys.toMutableList()
        }.getOrNull()
    }
}
