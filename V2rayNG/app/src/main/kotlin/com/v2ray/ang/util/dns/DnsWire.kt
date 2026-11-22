package com.v2ray.ang.util.dns

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.InetAddress
import java.nio.charset.StandardCharsets

internal enum class DnsType(val code: Int) {
    A(1), NS(2), CNAME(5), SOA(6), MX(15), TXT(16), AAAA(28);
    companion object {
        fun fromCode(value: Int): DnsType? = values().firstOrNull { it.code == value }
    }
}

/** Display strings may be shortened; comparison must use the full wire value. */
internal data class DnsRecord(val name: String, val type: DnsType, val ttl: Long, val value: String,
    val comparisonValue: String = value)
internal data class DnsMessage(
    val rcode: Int,
    val truncated: Boolean,
    val authoritative: Boolean,
    val recursionAvailable: Boolean,
    val authenticatedData: Boolean,
    val edns: Boolean,
    val records: List<DnsRecord>,
    val questionName: String,
    val questionType: Int,
)

/** Stable codes only; never include untrusted packet or resolver content in an exception. */
internal class DnsWireException(val code: String) : Exception(code)

/** RFC 1035 DNS wire support for bounded, read-only diagnostics. */
internal object DnsWire {
    fun query(domain: String, type: DnsType, transactionId: Int): ByteArray {
        require(transactionId in 0..65535)
        val ascii = try {
            IDN.toASCII(domain.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            throw DnsWireException("INVALID_DOMAIN")
        }
        if (ascii.isBlank() || ascii.length > 253) throw DnsWireException("INVALID_DOMAIN")
        val labels = ascii.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) throw DnsWireException("INVALID_DOMAIN")
        return ByteArrayOutputStream().apply {
            writeU16(this, transactionId)
            writeU16(this, 0x0100) // recursion desired
            writeU16(this, 1) // one question
            writeU16(this, 0)
            writeU16(this, 0)
            writeU16(this, 1) // one EDNS(0) option record
            for (label in labels) {
                val octets = label.toByteArray(StandardCharsets.US_ASCII)
                write(octets.size)
                write(octets)
            }
            write(0)
            writeU16(this, type.code)
            writeU16(this, 1) // IN
            write(0) // root OPT owner
            writeU16(this, 41) // OPT
            writeU16(this, 1232) // conservative UDP payload size
            writeU32(this, 0L) // no DO flag or extended RCODE
            writeU16(this, 0)
        }.toByteArray()
    }

    fun isTruncated(packet: ByteArray, transactionId: Int): Boolean {
        validateHeader(packet, transactionId)
        return ((u16(packet, 2) and 0x0200) != 0)
    }

    fun decode(packet: ByteArray, transactionId: Int): DnsMessage {
        validateHeader(packet, transactionId)
        val flags = u16(packet, 2)
        val questions = u16(packet, 4)
        val answers = u16(packet, 6)
        val authorities = u16(packet, 8)
        val extras = u16(packet, 10)
        if (questions != 1 || answers > 256 || authorities > 256 || extras > 256) {
            throw DnsWireException("RESPONSE_TOO_LARGE")
        }
        val (questionName, questionEnd) = readName(packet, 12)
        requireBytes(packet, questionEnd, 4)
        val questionType = u16(packet, questionEnd)
        val questionClass = u16(packet, questionEnd + 2)
        if (questionClass != 1) throw DnsWireException("INVALID_QUESTION")
        var pos = questionEnd + 4
        val records = ArrayList<DnsRecord>(minOf(answers, 32))
        var edns = false
        var extendedRcode = 0
        for (section in 0..2) {
            val count = when (section) { 0 -> answers; 1 -> authorities; else -> extras }
            repeat(count) {
                val (owner, next) = readName(packet, pos)
                requireBytes(packet, next, 10)
                val type = u16(packet, next)
                val clazz = u16(packet, next + 2)
                val ttl = u32(packet, next + 4)
                val length = u16(packet, next + 8)
                val begin = next + 10
                requireBytes(packet, begin, length)
                val end = begin + length
                if (section == 2 && type == 41) {
                    edns = true
                    extendedRcode = ((ttl ushr 24) and 255).toInt()
                }
                if (section == 0 && clazz == 1) {
                    val known = DnsType.fromCode(type)
                    if (known != null) {
                        val value = readRecordValue(packet, begin, end, known)
                        if (value != null) {
                            records.add(DnsRecord(owner, known, ttl, value.value, value.comparison))
                        }
                    }
                }
                pos = end
            }
        }
        return DnsMessage(
            rcode = (flags and 15) or (extendedRcode shl 4),
            truncated = (flags and 0x0200) != 0,
            authoritative = (flags and 0x0400) != 0,
            recursionAvailable = (flags and 0x0080) != 0,
            authenticatedData = (flags and 0x0020) != 0,
            edns = edns,
            records = records,
            questionName = questionName,
            questionType = questionType,
        )
    }

    private data class RData(val value: String, val comparison: String = value)

    private fun readRecordValue(packet: ByteArray, start: Int, end: Int, type: DnsType): RData? = when (type) {
        DnsType.A -> if (end - start == 4) RData(InetAddress.getByAddress(packet.copyOfRange(start, end)).hostAddress) else throw DnsWireException("MALFORMED_A")
        DnsType.AAAA -> if (end - start == 16) RData(InetAddress.getByAddress(packet.copyOfRange(start, end)).hostAddress) else throw DnsWireException("MALFORMED_AAAA")
        DnsType.NS, DnsType.CNAME -> {
            val (name, next) = readName(packet, start)
            if (next > end) throw DnsWireException("INVALID_RDATA")
            RData(name)
        }
        DnsType.MX -> {
            if (end - start < 3) throw DnsWireException("INVALID_RDATA")
            val (host, next) = readName(packet, start + 2)
            if (next > end) throw DnsWireException("INVALID_RDATA")
            RData("${u16(packet, start)} $host")
        }
        DnsType.TXT -> {
            val strings = mutableListOf<String>()
            val canonical = StringBuilder()
            var pos = start
            while (pos < end) {
                val length = packet[pos].toInt() and 255
                pos++
                if (pos + length > end) throw DnsWireException("INVALID_RDATA")
                // Length-prefixed raw bytes distinguish ["ab","cd"] from ["ab cd"],
                // preserve invalid UTF-8, and do not lose differences past 512 chars.
                canonical.append(length.toString(16).padStart(2, '0')).append(':')
                repeat(length) { offset ->
                    canonical.append((packet[pos + offset].toInt() and 255).toString(16).padStart(2, '0'))
                }
                strings.add(String(packet, pos, length, StandardCharsets.UTF_8).take(256))
                pos += length
            }
            RData(strings.joinToString(" ").take(512), canonical.toString())
        }
        DnsType.SOA -> {
            val (primary, afterPrimary) = readName(packet, start)
            if (afterPrimary > end) throw DnsWireException("INVALID_RDATA")
            val (mailbox, afterMailbox) = readName(packet, afterPrimary)
            if (afterMailbox > end || afterMailbox + 20 != end) throw DnsWireException("INVALID_RDATA")
            RData("$primary $mailbox serial=${u32(packet, afterMailbox)} " +
                "refresh=${u32(packet, afterMailbox + 4)} retry=${u32(packet, afterMailbox + 8)} " +
                "expire=${u32(packet, afterMailbox + 12)} minimum=${u32(packet, afterMailbox + 16)}")
        }
    }

    private fun readName(packet: ByteArray, start: Int): Pair<String, Int> {
        var at = start
        var next = -1
        var hops = 0
        val visited = HashSet<Int>()
        val labels = ArrayList<String>()
        while (true) {
            requireBytes(packet, at, 1)
            if (++hops > 128 || !visited.add(at)) throw DnsWireException("NAME_POINTER_LOOP")
            val value = packet[at].toInt() and 255
            when {
                value == 0 -> {
                    val result = labels.joinToString(".")
                    if (result.length > 253) throw DnsWireException("NAME_TOO_LONG")
                    return result to (if (next < 0) at + 1 else next)
                }
                value and 0xc0 == 0xc0 -> {
                    requireBytes(packet, at, 2)
                    val pointer = ((value and 0x3f) shl 8) or (packet[at + 1].toInt() and 255)
                    if (pointer >= packet.size) throw DnsWireException("INVALID_POINTER")
                    if (next < 0) next = at + 2
                    at = pointer
                }
                value and 0xc0 != 0 -> throw DnsWireException("INVALID_LABEL")
                else -> {
                    if (value > 63) throw DnsWireException("INVALID_LABEL")
                    requireBytes(packet, at + 1, value)
                    labels.add(String(packet, at + 1, value, StandardCharsets.US_ASCII))
                    at += 1 + value
                }
            }
        }
    }

    private fun validateHeader(packet: ByteArray, transactionId: Int) {
        requireBytes(packet, 0, 12)
        if (u16(packet, 0) != transactionId || transactionId !in 0..65535) throw DnsWireException("TRANSACTION_MISMATCH")
        if (u16(packet, 2) and 0x8000 == 0) throw DnsWireException("NOT_A_RESPONSE")
        if ((u16(packet, 2) ushr 11) and 15 != 0) throw DnsWireException("INVALID_OPCODE")
    }

    private fun requireBytes(data: ByteArray, index: Int, count: Int) {
        if (index < 0 || count < 0 || index.toLong() + count > data.size) throw DnsWireException("TRUNCATED_PACKET")
    }
    private fun u16(data: ByteArray, index: Int): Int {
        requireBytes(data, index, 2)
        return ((data[index].toInt() and 255) shl 8) or (data[index + 1].toInt() and 255)
    }
    private fun u32(data: ByteArray, index: Int): Long {
        requireBytes(data, index, 4)
        return ((u16(data, index).toLong() shl 16) or u16(data, index + 2).toLong())
    }
    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 255)
        out.write(value and 255)
    }
    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        writeU16(out, (value ushr 16).toInt())
        writeU16(out, value.toInt())
    }
}
