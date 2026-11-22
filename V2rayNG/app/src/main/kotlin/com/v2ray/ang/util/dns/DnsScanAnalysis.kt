package com.v2ray.ang.util.dns

import java.util.Locale

/** Observed answer differences are not proof of DNS leaks or misrouting. */
internal data class DnsScanSummary(
    val validResponses: Int,
    val timeouts: Int,
    val tcpFallbacks: Int,
    val mismatchedGroups: Int,
    val mismatchedResultIndices: Set<Int>,
)

internal object DnsScanAnalysis {
    private data class Question(val domain: String, val type: DnsType, val route: DnsRoute)
    private data class Record(val owner: String, val type: DnsType, val value: String)
    private data class Answer(val rcode: Int, val records: List<Record>)

    fun summarize(results: List<DnsProbeResult>): DnsScanSummary {
        // Timeouts, SERVFAIL and transport failures are not comparable answers.
        val comparable = results.withIndex().filter { observation ->
            val response = observation.value.answer
            response != null && response.rcode in setOf(0, 3) &&
                observation.value.status in setOf("OK", "NO_ANSWER", "NXDOMAIN")
        }
        // Compare only the same domain, record type and explicitly selected route.
        val groups = comparable.groupBy { indexed ->
            Question(indexed.value.domain.trimEnd('.').lowercase(Locale.ROOT),
                indexed.value.qtype, indexed.value.route)
        }
        val mismatched = mutableSetOf<Int>()
        var disagreementCount = 0
        for (group in groups.values) {
            val distinctResolvers = group.distinctBy { it.value.resolver.lowercase(Locale.ROOT) }
            if (distinctResolvers.size < 2) continue
            val answers = distinctResolvers.map { comparableAnswer(it.value.answer!!) }
            if (answers.distinct().size > 1) {
                disagreementCount++
                mismatched.addAll(group.map { it.index })
            }
        }
        return DnsScanSummary(
            validResponses = comparable.size,
            timeouts = results.count { it.status == "TIMEOUT" },
            tcpFallbacks = results.count { result ->
                result.attempts.any { it.transport == "UDP" && it.status == "TRUNCATED" }
            },
            mismatchedGroups = disagreementCount,
            mismatchedResultIndices = mismatched,
        )
    }

    private fun comparableAnswer(answer: DnsMessage): Answer = Answer(
        answer.rcode,
        answer.records.map { record ->
            Record(record.name.trimEnd('.').lowercase(Locale.ROOT), record.type,
                if (record.type == DnsType.TXT) record.comparisonValue
                else record.value.lowercase(Locale.ROOT))
        }.distinct().sortedWith(compareBy({ it.owner }, { it.type.code }, { it.value }))
    )
}
