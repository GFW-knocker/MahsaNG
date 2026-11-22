package com.v2ray.ang.util

import com.google.gson.Gson
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.dto.V2rayConfig

/**
 * Full connection identity for safe selection after a subscription refresh.
 *
 * A selected outbound can delegate to additional outbounds via proxySettings.tag
 * or streamSettings.sockopt.dialerProxy. Comparing only the selected outbound
 * would ignore the delegated credentials. Resolve every referenced outbound
 * transitively and reject dangling, ambiguous, malformed or cyclic references.
 *
 * Without chain references, preserve the existing serialized outbound identity.
 * Nothing here modifies a profile or persists credential information.
 */
internal object SubscriptionOutboundIdentity {
    fun serialize(profile: ServerConfig, gson: Gson): String? = runCatching {
        val primary = profile.getProxyOutbound() ?: return@runCatching null
        if (profile.configType == EConfigType.CUSTOM) {
            // Xray uses the first outbound when no routing rule matches.
            // getProxyOutbound() instead finds the first recognized proxy type.
            // If these differ, the fallback may silently change accounts;
            // do not carry the old selection without a complete identity.
            val fallback = profile.fullConfig?.outbounds?.firstOrNull()
                ?: return@runCatching null
            if (fallback !== primary) return@runCatching null
        }
        val directIdentity = gson.toJson(primary)
        val directReferences = references(primary, gson) ?: return@runCatching null
        // CUSTOM routing can select outbounds beyond the primary or its chain.
        val routing = if (profile.configType == EConfigType.CUSTOM) {
            profile.fullConfig?.routing ?: return@runCatching null
        } else null
        val routedTags = mutableSetOf<String>()
        if (routing != null) {
            for (rule in routing.rules) {
                // A balancer's runtime choice cannot be safely resolved here.
                if (!rule.balancerTag.isNullOrBlank() || rule.outboundTag.isBlank()) {
                    return@runCatching null
                }
                routedTags.add(rule.outboundTag)
            }
        }
        if (directReferences.isEmpty() && routedTags.isEmpty()) return@runCatching directIdentity

        // Unresolvable or ambiguous routing/chain targets must fail closed.
        val candidates = profile.fullConfig?.outbounds ?: return@runCatching null
        val byTag = candidates.groupBy { it.tag }
        if (routedTags.any { byTag[it]?.size != 1 }) return@runCatching null
        val allReferences = (directReferences + routedTags.filter { it != primary.tag }).distinct()
        val resolved = mutableMapOf<String, V2rayConfig.OutboundBean>()
        val visiting = mutableSetOf(primary.tag)

        fun visit(tag: String): Boolean {
            if (tag in visiting) return false // a cycle through the selected outbound or chain
            if (tag in resolved) return true
            // Duplicate tags are ambiguous: never guess which account will be used.
            val next = byTag[tag]?.singleOrNull() ?: return false
            visiting.add(tag)
            val refs = references(next, gson) ?: return false
            if (!refs.all(::visit)) return false
            visiting.remove(tag)
            resolved[tag] = next
            return true
        }

        if (!allReferences.all(::visit)) return@runCatching null
        // Sorting by tag makes identity independent of unrelated outbound order.
        val identity = linkedMapOf<String, Any>(
            "primary" to primary,
            "referencedOutbounds" to resolved.toSortedMap(),
        )
        // Include routing conditions so changing destinations cannot silently retarget.
        if (routedTags.isNotEmpty() && routing != null) identity["routing"] = routing
        gson.toJson(identity)
    }.getOrNull()

    private fun references(outbound: V2rayConfig.OutboundBean, gson: Gson): List<String>? {
        val result = mutableListOf<String>()
        outbound.proxySettings?.let { settings ->
            val json = gson.toJsonTree(settings)
            if (!json.isJsonObject) return null
            val value = json.asJsonObject.get("tag")
            if (value == null || !value.isJsonPrimitive ||
                !value.asJsonPrimitive.isString || value.asString.isBlank()
            ) return null
            result.add(value.asString)
        }
        outbound.streamSettings?.sockopt?.dialerProxy?.let { tag ->
            if (tag.isBlank()) return null
            result.add(tag)
        }
        return result.distinct()
    }
}
