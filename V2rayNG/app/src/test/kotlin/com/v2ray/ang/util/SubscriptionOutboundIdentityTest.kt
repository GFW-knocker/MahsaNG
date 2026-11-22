package com.v2ray.ang.util

import com.google.gson.Gson
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-module regression: exercise real outbound DTOs and Gson, not handwritten JSON. */
class SubscriptionOutboundIdentityTest {
    private val outboundEncoder = Gson()

    private fun serverConfig(credential: String = "account-original",
                             sni: String = "original.example"): ServerConfig {
        val config = ServerConfig.create(EConfigType.VLESS)
        val outbound = requireNotNull(config.getProxyOutbound())
        val destination = requireNotNull(outbound.settings?.vnext).first()
        destination.address = "edge.example.org"
        destination.port = 443
        destination.users.first().id = credential
        val stream = requireNotNull(outbound.streamSettings)
        stream.security = V2rayConfig.TLS
        stream.tlsSettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(serverName = sni)
        return config
    }

    /** Exercise the same graph-aware serializer used by MmkvManager. */
    private fun sameIdentity(before: ServerConfig, after: ServerConfig): Boolean =
        SubscriptionReplacementPlan.matchesOutbound(
            SubscriptionOutboundIdentity.serialize(before, outboundEncoder),
            SubscriptionOutboundIdentity.serialize(after, outboundEncoder),
        )

    @Test fun changedCredentialsAndTlsDoNotRetargetSelectionAtSameEndpoint() {
        val original = serverConfig()
        val wrongAccount = serverConfig(credential = "account-other")
        val wrongTls = serverConfig(sni = "changed.example")
        val identical = serverConfig()

        assertEquals(original.getProxyOutbound()?.getServerAddress(),
            wrongAccount.getProxyOutbound()?.getServerAddress())
        assertEquals(original.getProxyOutbound()?.getServerPort(),
            wrongTls.getProxyOutbound()?.getServerPort())
        assertNotEquals(outboundEncoder.toJson(original.getProxyOutbound()),
            outboundEncoder.toJson(wrongAccount.getProxyOutbound()))
        assertNotEquals(outboundEncoder.toJson(original.getProxyOutbound()),
            outboundEncoder.toJson(wrongTls.getProxyOutbound()))

        val match = SubscriptionReplacementPlan.matchSelected(
            selectedGuid = "old",
            removedGuids = setOf("old"),
            selectedConfig = original,
            newConfigs = linkedMapOf(
                "wrong-account" to wrongAccount,
                "wrong-tls" to wrongTls,
                "same-account" to identical,
            ),
            sameEndpoint = ::sameIdentity,
        )
        assertEquals("same-account", match)
        assertNull(SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), original,
            mapOf("wrong-account" to wrongAccount, "wrong-tls" to wrongTls),
            ::sameIdentity,
        ))
        assertFalse(sameIdentity(ServerConfig.create(EConfigType.CUSTOM),
            ServerConfig.create(EConfigType.CUSTOM)))
    }

    private fun proxy(
        proxyTag: String? = null,
        dialerTag: String? = null,
    ): V2rayConfig.OutboundBean {
        val outbound = requireNotNull(serverConfig().getProxyOutbound())
            .copy(proxySettings = proxyTag?.let { mapOf("tag" to it) })
        if (dialerTag != null) {
            outbound.streamSettings?.sockopt =
                V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(dialerProxy = dialerTag)
        }
        return outbound
    }

    private fun bridge(
        password: String,
        tag: String = "bridge",
        proxyTag: String? = null,
        dialerTag: String? = null,
    ): V2rayConfig.OutboundBean {
        val stream = V2rayConfig.OutboundBean.StreamSettingsBean()
        if (dialerTag != null) {
            stream.sockopt = V2rayConfig.OutboundBean.StreamSettingsBean.SockoptBean(
                dialerProxy = dialerTag,
            )
        }
        return V2rayConfig.OutboundBean(
            tag = tag,
            protocol = "trojan",
            settings = V2rayConfig.OutboundBean.OutSettingsBean(
                servers = listOf(V2rayConfig.OutboundBean.OutSettingsBean.ServersBean(
                    address = "bridge.example.org",
                    port = 443,
                    password = password,
                )),
            ),
            streamSettings = stream,
            proxySettings = proxyTag?.let { mapOf("tag" to it) },
        )
    }

    private fun custom(
        proxy: V2rayConfig.OutboundBean,
        additionalOutbounds: List<V2rayConfig.OutboundBean>,
        rules: List<V2rayConfig.RoutingBean.RulesBean> = emptyList(),
    ): ServerConfig {
        val config = ServerConfig.create(EConfigType.CUSTOM)
        config.fullConfig = V2rayConfig(
            log = V2rayConfig.LogBean(access = "", error = "", loglevel = "warning"),
            policy = null,
            inbounds = arrayListOf(),
            outbounds = ArrayList(listOf(proxy) + additionalOutbounds),
            dns = V2rayConfig.DnsBean(),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = ArrayList(rules)),
        )
        return config
    }

    @Test fun referencedProxySettingsCredentialChangesCannotRetargetSelection() {
        val original = custom(proxy(proxyTag = "bridge"), listOf(bridge("original-password")))
        val wrongAccount = custom(proxy(proxyTag = "bridge"), listOf(bridge("other-password")))
        val unchanged = custom(proxy(proxyTag = "bridge"), listOf(bridge("original-password")))
        // The former comparison saw these primary outbounds as identical.
        assertEquals(
            outboundEncoder.toJson(original.getProxyOutbound()),
            outboundEncoder.toJson(wrongAccount.getProxyOutbound()),
        )
        assertFalse(sameIdentity(original, wrongAccount))
        assertEquals("unchanged", SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), original,
            linkedMapOf("wrong" to wrongAccount, "unchanged" to unchanged),
            ::sameIdentity,
        ))
        assertNull(SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), original, mapOf("wrong" to wrongAccount), ::sameIdentity,
        ))
    }

    @Test fun sockoptDialerProxyAndTransitiveChainCredentialsArePartOfIdentity() {
        val original = custom(proxy(dialerTag = "bridge"),
            listOf(bridge("outer", dialerTag = "egress"), bridge("inner", tag = "egress")))
        val altered = custom(proxy(dialerTag = "bridge"),
            listOf(bridge("outer", dialerTag = "egress"), bridge("other-inner", tag = "egress")))
        val same = custom(proxy(dialerTag = "bridge"),
            listOf(bridge("outer", dialerTag = "egress"), bridge("inner", tag = "egress")))
        assertFalse(sameIdentity(original, altered))
        assertEquals("same", SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), original,
            linkedMapOf("wrong" to altered, "same" to same), ::sameIdentity,
        ))
    }

    @Test fun danglingDuplicateMalformedAndCyclicChainReferencesFailClosed() {
        val dangling = custom(proxy(proxyTag = "missing"), listOf(bridge("pass")))
        val ambiguous = custom(proxy(proxyTag = "bridge"),
            listOf(bridge("pass"), bridge("pass")))
        val cyclic = custom(proxy(proxyTag = "bridge"),
            listOf(bridge("pass", proxyTag = "proxy")))
        val malformed = custom(proxy(proxyTag = "bridge").copy(proxySettings = mapOf("wrong" to "bridge")),
            listOf(bridge("pass")))
        for (candidate in listOf(dangling, ambiguous, cyclic, malformed)) {
            assertNull(SubscriptionOutboundIdentity.serialize(candidate, outboundEncoder))
            assertFalse(sameIdentity(candidate, candidate))
        }
    }

    @Test fun unrelatedOutboundsDoNotChangeIdentityWithoutChainReferences() {
        val before = custom(proxy(), listOf(bridge("unrelated-original")))
        val after = custom(proxy(), listOf(bridge("unrelated-changed")))
        assertEquals(
            SubscriptionOutboundIdentity.serialize(before, outboundEncoder),
            SubscriptionOutboundIdentity.serialize(after, outboundEncoder),
        )
        assertEquals("new", SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), before, mapOf("new" to after), ::sameIdentity,
        ))
    }

    @Test fun routedOutboundCredentialsCannotRetargetSelection() {
        val rules = listOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = "routed"))
        val selected = custom(proxy(), listOf(bridge("original", tag = "routed")), rules)
        val changed = custom(proxy(), listOf(bridge("other-account", tag = "routed")), rules)
        val unchanged = custom(proxy(), listOf(bridge("original", tag = "routed")), rules)
        assertEquals(outboundEncoder.toJson(selected.getProxyOutbound()),
            outboundEncoder.toJson(changed.getProxyOutbound()))
        assertFalse(sameIdentity(selected, changed))
        assertEquals("same", SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), selected,
            linkedMapOf("wrong" to changed, "same" to unchanged), ::sameIdentity,
        ))
    }

    @Test fun routedOutboundChainCredentialsAreResolvedTransitively() {
        val rules = listOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = "routed"))
        val before = custom(proxy(), listOf(
            bridge("middle", tag = "routed", dialerTag = "egress"),
            bridge("original", tag = "egress")), rules)
        val after = custom(proxy(), listOf(
            bridge("middle", tag = "routed", dialerTag = "egress"),
            bridge("changed", tag = "egress")), rules)
        assertFalse(sameIdentity(before, after))
    }

    @Test fun unresolvedRoutingAndDynamicBalancersFailClosed() {
        val missing = custom(proxy(), listOf(bridge("password")),
            listOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = "not-present")))
        val dynamic = custom(proxy(), listOf(bridge("password")),
            listOf(V2rayConfig.RoutingBean.RulesBean(balancerTag = "dynamic")))
        for (profile in listOf(missing, dynamic)) {
            assertNull(SubscriptionOutboundIdentity.serialize(profile, outboundEncoder))
            assertFalse(sameIdentity(profile, profile))
        }
    }

    @Test fun routingToPrimaryIsNotAnOutboundChainCycle() {
        val rules = listOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = "proxy"))
        assertTrue(sameIdentity(
            custom(proxy(), emptyList(), rules), custom(proxy(), emptyList(), rules)))
    }

    @Test fun customWithDifferentFallbackOutboundFailsClosed() {
        // Xray's default path uses the first outbound, not the first proxy.
        val original = custom(proxy(), emptyList())
        val matchingProxyWithChangedFallback = custom(proxy(), emptyList())
        val direct = V2rayConfig.OutboundBean(tag = "direct", protocol = "freedom")
        original.fullConfig!!.outbounds.add(0, direct)
        matchingProxyWithChangedFallback.fullConfig!!.outbounds.add(0,
            direct.copy(sendThrough = "192.0.2.10"))
        assertEquals(
            outboundEncoder.toJson(original.getProxyOutbound()),
            outboundEncoder.toJson(matchingProxyWithChangedFallback.getProxyOutbound()),
        )
        assertNull(SubscriptionOutboundIdentity.serialize(original, outboundEncoder))
        assertFalse(sameIdentity(original, matchingProxyWithChangedFallback))
        assertNull(SubscriptionReplacementPlan.matchSelected(
            "old", setOf("old"), original,
            mapOf("new" to matchingProxyWithChangedFallback), ::sameIdentity,
        ))
    }
}
