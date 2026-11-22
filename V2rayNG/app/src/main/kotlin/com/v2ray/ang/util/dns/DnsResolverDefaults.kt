package com.v2ray.ang.util.dns

/**
 * A credential-safe view of the configured Xray DNS entries. Only literal-IP
 * endpoints are retained: DoH/DoQ/TCP URLs may carry credentials or private
 * paths and must never be echoed in UI diagnostics.
 *
 * These are settings, not evidence that a specific active profile used them.
 */
internal data class DnsConfiguredResolvers(
    val numericResolvers: List<String>,
    val unsupportedCount: Int,
) {
    val suggestedInput: String get() = numericResolvers.take(4).joinToString(",")
    val additionalNumericCount: Int get() = (numericResolvers.size - 4).coerceAtLeast(0)
}

internal object DnsResolverDefaults {
    fun fromConfiguredEntries(configured: List<String>): DnsConfiguredResolvers {
        val numeric = LinkedHashSet<String>()
        var unsupported = 0
        for (entry in configured) {
            if (entry.isBlank()) continue
            val endpoint = runCatching { DnsResolverInput.parse(entry) }.getOrNull()
            if (endpoint == null) unsupported++ else numeric.add(endpoint.label)
        }
        return DnsConfiguredResolvers(numeric.toList(), unsupported)
    }
}
