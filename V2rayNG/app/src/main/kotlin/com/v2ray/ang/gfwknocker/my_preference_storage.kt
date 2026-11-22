package com.v2ray.ang.gfwknocker

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * Legacy GFW settings interface used by Java and Kotlin callers.
 * The public fork references this class but contains no implementation.
 * MMKV default storage retains existing plain string keys; multi-process
 * mode prevents reading them as single-process-only data from the VPN service.
 */
@Suppress("ClassName", "UNUSED_PARAMETER")
class my_preference_storage @JvmOverloads constructor(context: Context? = null) {
    private val prefs: MMKV
        get() = requireNotNull(MMKV.defaultMMKV(MMKV.MULTI_PROCESS_MODE, null)) {
            "MMKV must be initialized in AngApplication before reading GFW settings"
        }

    fun get_value(key: String, fallback: String): String {
        return prefs.decodeString(key, fallback) ?: fallback
    }

    fun put_value(key: String, value: String) {
        check(prefs.encode(key, value)) { "Failed to persist GFW setting" }
    }

    fun get_array(key: String): Array<String> {
        return GfwPreferenceArrayCodec.decode(prefs.decodeString(key + "_ordered_array_v1", null))
    }

    fun put_array(key: String, values: Array<String>) {
        val encoded = GfwPreferenceArrayCodec.encode(values)
        check(prefs.encode(key + "_ordered_array_v1", encoded)) {
            "Failed to persist ordered GFW settings"
        }
    }
}
