package com.v2ray.ang.util

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** Provenance of each explicitly requested target; expansions never import VPN profiles. */
internal enum class TargetSource { IPV4_SINGLE, IPV4_CIDR, IPV4_RANGE, IPV6_LITERAL, HOSTNAME }

internal data class TargetDiscoveryTarget(
    val host: String,
    val port: Int,
    val sni: String?,
    val verifyTls: Boolean,
    val source: TargetSource,
)

/**
 * Target-first bounded planner, adapted from MaybeScanner's target-expansion design.
 * Planning is offline: it never resolves domains, opens sockets or silently truncates ranges.
 * Raw IP scans have no invented SNI; TLS on an IP requires an explicitly entered domain.
 */
internal object TargetDiscoveryPlanner {
    const val MAX_TARGETS = 128
    const val MAX_INPUT_CHARS = 4096
    private val split = Regex("[,;\\s]+")
    private val octet = Regex("(?:0|[1-9][0-9]{0,2})")
    private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val ipv6Chars = Regex("[0-9a-fA-F:.]+")
    private const val IPV4_ALL = 0xffffffffL

    fun plan(raw: String, port: Int, verifyTls: Boolean, sniInput: String? = null): List<TargetDiscoveryTarget> {
        require(raw.isNotBlank() && raw.length <= MAX_INPUT_CHARS)
        require(port in 1..65535)
        val tokens = raw.trim().split(split).filter(String::isNotBlank)
        require(tokens.size in 1..MAX_TARGETS)
        val explicitSni = sniInput?.trim()?.takeIf(String::isNotEmpty)?.let(::hostname)
        require(verifyTls || explicitSni == null) { "SNI requires an enabled TLS test" }
        val seen = LinkedHashSet<String>()
        val planned = ArrayList<TargetDiscoveryTarget>()
        for (token in tokens) {
            val expanded = expand(token, MAX_TARGETS - planned.size)
            for ((host, source) in expanded) {
                if (!seen.add(host)) continue
                val sni = if (verifyTls) {
                    explicitSni ?: if (source == TargetSource.HOSTNAME) host
                    else throw IllegalArgumentException("IP TLS testing needs an explicit SNI")
                } else null
                planned.add(TargetDiscoveryTarget(host, port, sni, verifyTls, source))
            }
        }
        require(planned.isNotEmpty() && planned.size <= MAX_TARGETS)
        return planned
    }

    /** Strict IPv4: leading zeroes and integers beyond 255 are never DNS names. */
    private fun ipv4(text: String): Long? {
        val segments = text.split('.')
        if (segments.size != 4 || segments.any { !octet.matches(it) }) return null
        val numbers = segments.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        return numbers.fold(0L) { current, n -> (current shl 8) or n.toLong() }
    }

    private fun stringifyIpv4(ip: Long): String =
        (24 downTo 0 step 8).joinToString(".") { ((ip ushr it) and 255).toString() }

    private fun hostname(raw: String): String {
        require(raw.isNotEmpty() && !raw.contains('/') && !raw.contains('@') &&
            !raw.contains(':') && !raw.contains('[') && !raw.contains(']'))
        val result = IDN.toASCII(raw.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
        // Require a dot to prevent treating mistyped space-separated words
        // or malformed numeric addresses as valid scan hostnames.
        require(result.length in 4..253 && '.' in result &&
            !result.matches(Regex("[0-9.]+")) &&
            result.split('.').all { it.length <= 63 && label.matches(it) })
        return result
    }

    private fun ipv6(raw: String): String {
        val value = if (raw.startsWith("[") && raw.endsWith("]")) {
            raw.substring(1, raw.length - 1)
        } else raw
        require(value.contains(':') && ipv6Chars.matches(value) && '%' !in value)
        // InetAddress throws UnknownHostException for malformed IPv6-looking
        // input such as "192.0.2.1:443". Treat it as invalid planning input,
        // not as a DNS/network probe failure.
        val address = runCatching { InetAddress.getByName(value) }
            .getOrElse { throw IllegalArgumentException("Invalid IPv6 literal") }
        require(address is Inet6Address)
        return address.hostAddress
    }

    private fun expand(token: String, remaining: Int): List<Pair<String, TargetSource>> {
        require(remaining > 0) { "Scan exceeds the target cap" }
        if ('/' in token) {
            val components = token.split('/')
            require(components.size == 2)
            val ip = ipv4(components[0]) ?: throw IllegalArgumentException("Only IPv4 CIDR supported")
            val bits = components[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid prefix")
            require(bits in 0..32)
            val count = 1L shl (32 - bits)
            require(count <= remaining)
            val mask = (IPV4_ALL shl (32 - bits)) and IPV4_ALL
            val base = ip and mask
            return (0 until count.toInt()).map { offset ->
                stringifyIpv4(base + offset) to TargetSource.IPV4_CIDR
            }
        }
        // A hyphen in a DNS label is valid; only a dotted-IPv4 prefix starts a range.
        if (Regex("^[0-9.]+-").containsMatchIn(token)) {
            val components = token.split('-')
            require(components.size == 2)
            val first = ipv4(components[0]) ?: throw IllegalArgumentException("Invalid range start")
            val last = ipv4(components[1]) ?: throw IllegalArgumentException("Invalid range end")
            val count = last - first + 1
            require(count in 1..remaining.toLong())
            return (0 until count.toInt()).map { offset ->
                stringifyIpv4(first + offset) to TargetSource.IPV4_RANGE
            }
        }
        ipv4(token)?.let { return listOf(stringifyIpv4(it) to TargetSource.IPV4_SINGLE) }
        if (':' in token || token.startsWith("[") || token.endsWith("]")) {
            return listOf(ipv6(token) to TargetSource.IPV6_LITERAL)
        }
        return listOf(hostname(token) to TargetSource.HOSTNAME)
    }
}
