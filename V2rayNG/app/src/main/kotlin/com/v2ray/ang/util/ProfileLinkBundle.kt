package com.v2ray.ang.util

/** Narrow, portable link archive. Not an MMKV database backup or app-settings snapshot. */
internal object ProfileLinkBundle {
    const val MAX_LINKS = 512
    const val MAX_LINK_BYTES = 8192
    const val MAX_TEXT_BYTES = 1024 * 1024
    private val schemes = listOf(
        "vmess://", "vless://", "ss://", "trojan://", "socks://", "wireguard://"
    )

    /**
     * Every nonblank line must be an exported proxy link. An HTTP subscription
     * URL, malformed link, or injected control byte rejects the entire archive,
     * instead of importing some lines and trusting the rest.
     *
     * URL scheme-level validation is not a guarantee that a particular profile
     * will parse: the native importer still validates full protocol parameters.
     */
    fun parse(raw: String): List<String>? {
        if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return null
        val entries = LinkedHashSet<String>()
        for (line in raw.split('\n')) {
            val entry = line.trim()
            if (entry.isBlank()) continue
            if (entry.toByteArray(Charsets.UTF_8).size > MAX_LINK_BYTES ||
                entry.any { it.isWhitespace() || Character.isISOControl(it) } ||
                schemes.none { entry.startsWith(it) && entry.length > it.length }
            ) return null
            entries.add(entry)
            if (entries.size > MAX_LINKS) return null
        }
        return entries.takeIf { it.isNotEmpty() }?.toList()
    }

    fun encode(links: List<String>): String? =
        if (links.size !in 1..MAX_LINKS) null
        else parse(links.joinToString("\n"))?.joinToString("\n")
}
