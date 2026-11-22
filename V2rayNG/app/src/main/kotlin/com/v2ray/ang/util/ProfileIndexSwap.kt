package com.v2ray.ang.util

/** Swap two visible profile GUIDs in the authoritative full index, not by filtered position. */
internal object ProfileIndexSwap {
    fun swap(current: List<String>, firstGuid: String, secondGuid: String): List<String>? {
        if (firstGuid.isBlank() || secondGuid.isBlank()) return null
        val firstIndex = current.indexOf(firstGuid)
        val secondIndex = current.indexOf(secondGuid)
        if (firstIndex < 0 || secondIndex < 0) return null
        if (firstIndex == secondIndex) return current.toList()
        return current.toMutableList().apply {
            this[firstIndex] = secondGuid
            this[secondIndex] = firstGuid
        }
    }
}
