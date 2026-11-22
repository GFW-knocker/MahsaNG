package com.v2ray.ang.gfwknocker

import java.util.Locale

/**
 * ISO-3166 country labels for the Psiphon settings UI only.
 * Does not choose or modify network endpoints.
 */
@Suppress("ClassName")
object GFW_client_ip {
    private fun validCountryCode(value: String?): String? {
        val country = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (country.length != 2 || !country.all { it in 'A'..'Z' }) return null
        return country.takeIf { it in Locale.getISOCountries() }
    }

    @JvmStatic
    fun countryCodeToName(code: String?): String {
        val country = validCountryCode(code) ?: return ""
        return Locale("", country).getDisplayCountry(Locale.ENGLISH)
    }

    @JvmStatic
    fun countryCodeToFlag(code: String?): String {
        val country = validCountryCode(code) ?: return ""
        val first = 0x1F1E6 + (country[0] - 'A')
        val second = 0x1F1E6 + (country[1] - 'A')
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
