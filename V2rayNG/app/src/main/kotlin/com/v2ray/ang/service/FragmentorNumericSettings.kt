package com.v2ray.ang.service

/** Shared input contract for the GFW fragmentor settings screen and native listener. */
object FragmentorNumericSettings {
    data class Parsed(val fragments: Int, val delaySeconds: Double)

    /** Ignore corrupt legacy spinner indices and stay within the currently available choices. */
    @JvmStatic
    fun selectedIndex(raw: String?, choiceCount: Int): Int {
        if (choiceCount <= 0) return 0
        return (raw?.trim()?.toIntOrNull() ?: 0).coerceIn(0, choiceCount - 1)
    }

    @JvmStatic
    fun parse(fragmentsText: String?, delayText: String?): Parsed? {
        val fragments = fragmentsText?.trim()?.toIntOrNull() ?: return null
        val delay = delayText?.trim()?.toDoubleOrNull() ?: return null
        if (fragments !in 1..2048 || !delay.isFinite() || delay !in 0.0..1.0) return null
        return Parsed(fragments, delay)
    }
}
