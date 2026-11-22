package com.v2ray.ang.util.fmt

data class EchLinkValues(val configList: String?, val serverKeys: String?, val forceQuery: String?)

/** Reversible TLS-ECH extension to VLESS/Trojan share-link query parameters. */
object EchLinkParameters {
    fun toQuery(
        security: String,
        configList: String?,
        serverKeys: String?,
        forceQuery: String?,
        encode: (String) -> String,
    ): Map<String, String> {
        if (!security.equals("tls", ignoreCase = true)) return emptyMap()
        return buildMap {
            configList?.let { put("echConfigList", encode(it)) }
            serverKeys?.let { put("echServerKeys", encode(it)) }
            forceQuery?.let { put("echForceQuery", encode(it)) }
        }
    }

    /** Query values have already been URL-decoded by the existing URI parser. */
    fun fromQuery(security: String, query: Map<String, String>): EchLinkValues {
        if (!security.equals("tls", ignoreCase = true)) return EchLinkValues(null, null, null)
        return EchLinkValues(query["echConfigList"], query["echServerKeys"], query["echForceQuery"])
    }
}
