package com.v2ray.ang.gfwknocker

/**
 * Lossless, bounded serialization for ordered settings arrays.
 * Lengths are UTF-16 code units (the same indexing used by String.substring).
 * Decode fails closed instead of leaking partially decoded settings into the UI.
 */
object GfwPreferenceArrayCodec {
    private const val PREFIX = "v1|"
    private const val MAX_ENTRIES = 4096
    private const val MAX_CHARS = 1_000_000

    @JvmStatic
    fun encode(values: Array<String>): String {
        require(values.size <= MAX_ENTRIES) { "Too many settings entries" }
        return buildString {
            append(PREFIX)
            append(values.size)
            append('|')
            for (value in values) {
                require(value.length <= MAX_CHARS) { "Settings entry too large" }
                append(value.length)
                append(':')
                append(value)
            }
            require(length <= MAX_CHARS) { "Settings array too large" }
        }
    }

    @JvmStatic
    fun decode(serialized: String?): Array<String> {
        if (serialized == null || serialized.length > MAX_CHARS || !serialized.startsWith(PREFIX)) {
            return emptyArray()
        }
        val countEnd = serialized.indexOf('|', PREFIX.length)
        if (countEnd < 0) return emptyArray()
        val count = serialized.substring(PREFIX.length, countEnd).toIntOrNull() ?: return emptyArray()
        if (count !in 0..MAX_ENTRIES) return emptyArray()
        var cursor = countEnd + 1
        val result = ArrayList<String>(count)
        repeat(count) {
            val colon = serialized.indexOf(':', cursor)
            if (colon < cursor || colon - cursor > 8) return emptyArray()
            val length = serialized.substring(cursor, colon).toIntOrNull() ?: return emptyArray()
            if (length !in 0..MAX_CHARS) return emptyArray()
            cursor = colon + 1
            if (length > serialized.length - cursor) return emptyArray()
            result.add(serialized.substring(cursor, cursor + length))
            cursor += length
        }
        return if (cursor == serialized.length) result.toTypedArray() else emptyArray()
    }
}
