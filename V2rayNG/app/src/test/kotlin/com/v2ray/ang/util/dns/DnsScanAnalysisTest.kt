package com.v2ray.ang.util.dns

import org.junit.Assert.*
import org.junit.Test

class DnsScanAnalysisTest {
    private fun observation(resolver: String, value: String?, ttl: Long = 60,
        route: DnsRoute = DnsRoute.APP_UDP_TCP, status: String = "OK",
        rcode: Int = 0, type: DnsType = DnsType.A,
        domain: String = "example.com"): DnsProbeResult {
        val records = value?.split("|")?.map { DnsRecord(domain, type, ttl, it) } ?: emptyList()
        val msg = if (status == "TIMEOUT") null else DnsMessage(
            rcode, false, false, true, false, false, records, domain, type.code)
        return DnsProbeResult(resolver, domain, type, status, 12, msg,
            listOf(DnsAttempt("UDP", 12, if (status == "TIMEOUT") status else "OK")), route)
    }

    @Test fun differentAnswersOnSameRouteAreFlaggedButNeverCalledLeaks() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", "203.0.113.2"),
            observation("8.8.8.8:53", "203.0.113.3")
        ))
        assertEquals(1, report.mismatchedGroups)
        assertEquals(setOf(0,1), report.mismatchedResultIndices)
    }

    @Test fun answerOrderingAndTtlDifferencesAreNotMismatch() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", "203.0.113.2|203.0.113.3", ttl = 60),
            observation("8.8.8.8:53", "203.0.113.3|203.0.113.2", ttl = 15)
        ))
        assertEquals(0, report.mismatchedGroups)
        assertEquals(emptySet<Int>(), report.mismatchedResultIndices)
    }

    @Test fun timeoutAndOneValidAnswerCannotEstablishMismatch() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", "203.0.113.2"),
            observation("8.8.8.8:53", null, status = "TIMEOUT")
        ))
        assertEquals(0, report.mismatchedGroups)
        assertEquals(1, report.timeouts)
    }

    @Test fun negativeDnsAnswerCanDifferFromValidAddressResponse() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", null, status = "NXDOMAIN", rcode = 3),
            observation("8.8.8.8:53", "203.0.113.2")
        ))
        assertEquals(1, report.mismatchedGroups)
    }

    @Test fun routeDomainAndRecordTypeBoundComparison() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", "203.0.113.2", route = DnsRoute.APP_UDP_TCP),
            observation("8.8.8.8:53", "203.0.113.3", route = DnsRoute.LOCAL_SOCKS5_TCP),
            observation("9.9.9.9:53", "203.0.113.4", type = DnsType.AAAA),
            observation("4.4.4.4:53", "203.0.113.5", domain = "other.example")
        ))
        assertEquals(0, report.mismatchedGroups)
    }

    @Test fun duplicateResolverDoesNotCountAsIndependentComparison() {
        val report = DnsScanAnalysis.summarize(listOf(
            observation("1.1.1.1:53", "203.0.113.2"),
            observation("1.1.1.1:53", "203.0.113.3")
        ))
        assertEquals(0, report.mismatchedGroups)
    }
}
