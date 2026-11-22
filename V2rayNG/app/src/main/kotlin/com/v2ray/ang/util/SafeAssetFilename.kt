package com.v2ray.ang.util

/**
 * Validates a document provider's untrusted DISPLAY_NAME before using it as
 * a single filename inside the app-private asset directory.
 */
internal object SafeAssetFilename {
    fun validate(displayName: String?): String? {
        val name = displayName?.trim() ?: return null
        if (name.isEmpty() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 255) return null
        if (name.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) return null
        return name
    }
}
