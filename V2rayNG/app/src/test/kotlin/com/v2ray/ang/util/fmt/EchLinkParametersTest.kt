package com.v2ray.ang.util.fmt

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

class EchLinkParametersTest {
    private val encode: (String) -> String = { URLEncoder.encode(it, "UTF-8") }
    private fun decoded(values: Map<String, String>) =
        values.mapValues { URLDecoder.decode(it.value, "UTF-8") }

    @Test fun preservesAllThreeTlsEchValuesIncludingReservedCharacters() {
        val config = "base64+/= &?\n"
        val keys = "keys+/=\n"
        val query = EchLinkParameters.toQuery("tls", config, keys, "false", encode)
        assertEquals(setOf("echConfigList", "echServerKeys", "echForceQuery"), query.keys)
        val values = EchLinkParameters.fromQuery("tls", decoded(query))
        assertEquals(config, values.configList)
        assertEquals(keys, values.serverKeys)
        assertEquals("false", values.forceQuery)
    }

    @Test fun noEchFieldsAreAddedForRealityOrOtherNonTlsStreams() {
        assertTrue(EchLinkParameters.toQuery("reality", "cfg", "keys", "true", encode).isEmpty())
        assertEquals(EchLinkValues(null, null, null),
            EchLinkParameters.fromQuery("reality", mapOf("echServerKeys" to "keys")))
        assertTrue(EchLinkParameters.toQuery("none", "cfg", null, null, encode).isEmpty())
    }

    @Test fun absentValuesRemainAbsentAndEmptyValuesRemainEmpty() {
        val query = EchLinkParameters.toQuery("tls", null, "", null, encode)
        assertEquals(mapOf("echServerKeys" to ""), query)
        assertEquals(EchLinkValues(null, "", null), EchLinkParameters.fromQuery("tls", decoded(query)))
    }
}
