package com.v2ray.ang.util

/**
 * Numeric range rules shared by the server editor and its test harness.
 * Reject malformed, reversed, zero and overflowing values rather than passing
 * corrupt fragment settings into a saved proxy profile.
 */
internal object ServerEditorInputRules {
    fun validPort(port: Int): Boolean = port in 1..65535

    fun requirePort(port: Int): Int {
        require(validPort(port)) { "Server port must be between 1 and 65535" }
        return port
    }

    fun validPositiveRange(input: String?): Boolean {
        val text = input?.trim() ?: return false
        if (!text.matches(Regex("""\d{1,5}(-\d{1,5})?"""))) return false
        val parts = text.split('-', limit = 2)
        val minimum = parts[0].toIntOrNull() ?: return false
        val maximum = (parts.getOrNull(1) ?: parts[0]).toIntOrNull() ?: return false
        return minimum in 1..65535 && maximum in minimum..65535
    }
}
