package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test

class PasswordProtectedLinkArchiveTest {
    private val sample = "vless://user@example.org:443?security=tls#example\nss://abcd@example.org:8388#other"

    @Test fun supportsPasswordProtectedRoundTripWithoutPlaintextInFile() {
        val password = "example-correct-horse-passphrase".toCharArray()
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val encrypted = PasswordProtectedLinkArchive.encrypt(bytes, password)
        assertFalse(encrypted.toList().containsSlice(bytes.toList()))
        assertFalse(encrypted.contentEquals(bytes))
        assertArrayEquals(bytes, PasswordProtectedLinkArchive.decrypt(encrypted, password))
    }

    @Test fun wrongPasswordAndModifiedCiphertextNeverReturnPartialPlaintext() {
        val encoded = PasswordProtectedLinkArchive.encrypt(sample.toByteArray(), "correct-password-long".toCharArray())
        assertNull(PasswordProtectedLinkArchive.decrypt(encoded, "totally-wrong-password".toCharArray()))
        val tampered = encoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertNull(PasswordProtectedLinkArchive.decrypt(tampered, "correct-password-long".toCharArray()))
    }

    @Test fun tamperedHeadersAreAuthenticatedAndUnknownVersionsRejected() {
        val encoded = PasswordProtectedLinkArchive.encrypt(sample.toByteArray(), "correct-password-long".toCharArray())
        for (position in listOf(0, 8, 9, 24)) {
            val damaged = encoded.copyOf().apply { this[position] = (this[position].toInt() xor 1).toByte() }
            assertNull(PasswordProtectedLinkArchive.decrypt(damaged, "correct-password-long".toCharArray()))
        }
    }

    @Test fun randomSaltAndNonceMakeRepeatedExportsDifferent() {
        val pass = "example-correct-horse".toCharArray()
        val first = PasswordProtectedLinkArchive.encrypt(sample.toByteArray(), pass)
        val second = PasswordProtectedLinkArchive.encrypt(sample.toByteArray(), pass)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(sample.toByteArray(), PasswordProtectedLinkArchive.decrypt(second, pass))
    }

    @Test fun rejectsWeakPasswordsEmptyArchivesAndOversizedInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordProtectedLinkArchive.encrypt(sample.toByteArray(), "short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PasswordProtectedLinkArchive.encrypt(byteArrayOf(), "a-strong-password-123".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PasswordProtectedLinkArchive.encrypt(ByteArray(PasswordProtectedLinkArchive.MAX_PLAINTEXT_BYTES + 1),
                "a-strong-password-123".toCharArray())
        }
        assertNull(PasswordProtectedLinkArchive.decrypt(byteArrayOf(1,2,3), "a-strong-password-123".toCharArray()))
        assertNull(PasswordProtectedLinkArchive.decrypt(
            ByteArray(PasswordProtectedLinkArchive.MAX_ARCHIVE_BYTES + 1), "a-strong-password-123".toCharArray()))
    }

    @Test fun exportLinkNormalizerDeduplicatesWithoutSilentPartialCorruption() {
        assertEquals(listOf("vless://a@example.com:443#x", "ss://b@example.com:443"),
            ProfileLinkBundle.parse(" vless://a@example.com:443#x \nss://b@example.com:443\nvless://a@example.com:443#x\n"))
        assertEquals("vless://a@example.com:443#x\nss://b@example.com:443",
            ProfileLinkBundle.encode(listOf("vless://a@example.com:443#x", "ss://b@example.com:443")))
    }

    @Test fun invalidLineOrSubscriptionUrlMakesEntireBundleInvalid() {
        for (bad in listOf("https://remote.example/subscription", "vmess://ok\nhttp://bad",
                "vless://ok\n<script>secret</script>", "vless://ok\nnot-a-link",
                "vless://ok\nss://a b", "vless://ok\u0000")) {
            assertNull(ProfileLinkBundle.parse(bad))
        }
    }

    @Test fun bundleLimitsAreEnforcedBeforeAnyImport() {
        assertNull(ProfileLinkBundle.parse(""))
        assertNull(ProfileLinkBundle.parse("vmess://" + "A".repeat(ProfileLinkBundle.MAX_LINK_BYTES)))
        val tooMany = (1..ProfileLinkBundle.MAX_LINKS + 1).joinToString("\n") { "vless://a$it@example.org:443" }
        assertNull(ProfileLinkBundle.parse(tooMany))
    }

    private fun <T> List<T>.containsSlice(other: List<T>): Boolean {
        if (other.size > size) return false
        return (0..size - other.size).any { i -> subList(i, i + other.size) == other }
    }
}
