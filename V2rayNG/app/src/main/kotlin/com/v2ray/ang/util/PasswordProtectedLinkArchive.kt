package com.v2ray.ang.util

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Independent password-encrypted link file for Android API 21+.
 * Layout: ASCII "MHSLNK01" (8) | version (1) | random salt (16) |
 * random GCM IV (12) | ciphertext and 16-byte GCM authentication tag.
 * The complete header is authenticated AAD; no key or credentials are stored.
 *
 * PBKDF2-HMAC-SHA1 is used for Android API 21 compatibility, not for the
 * encryption primitive (AES-256-GCM); each file has an independent random salt.
 */
internal object PasswordProtectedLinkArchive {
    const val MAX_PLAINTEXT_BYTES = ProfileLinkBundle.MAX_TEXT_BYTES
    const val MAX_ARCHIVE_BYTES = MAX_PLAINTEXT_BYTES + 64
    const val MIN_PASSWORD_LENGTH = 12
    private const val MAX_PASSWORD_LENGTH = 256
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = "MHSLNK01".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Byte = 1
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = MAGIC + byteArrayOf(VERSION) + salt + iv
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    /** Wrong password, damaged header, malformed input, and tampering all fail closed. */
    fun decrypt(archive: ByteArray, password: CharArray): ByteArray? {
        val prefix = MAGIC.size + 1 + SALT_BYTES + IV_BYTES
        if (archive.size !in (prefix + TAG_BITS / 8)..MAX_ARCHIVE_BYTES ||
            password.size !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH
        ) return null
        if (!archive.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
            archive[MAGIC.size] != VERSION
        ) return null
        return runCatching {
            val header = archive.copyOfRange(0, prefix)
            val salt = archive.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + SALT_BYTES)
            val iv = archive.copyOfRange(MAGIC.size + 1 + SALT_BYTES, prefix)
            val key = derive(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(archive, prefix, archive.size - prefix)
                .takeIf { it.size in 1..MAX_PLAINTEXT_BYTES }
        }.getOrNull()
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
