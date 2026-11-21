package com.example.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EcKeyPairEncoded(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
    val fingerprint: String
)

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class PasswordHashResult(
    val hashBase64: String,
    val saltBase64: String
)

object CryptoEngine {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS = 256
    private val secureRandom = SecureRandom()

    fun hashPassword(password: CharArray): PasswordHashResult {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val hash = pbkdf2(password, salt)
        return PasswordHashResult(
            hashBase64 = encodeBase64(hash),
            saltBase64 = encodeBase64(salt)
        )
    }

    fun verifyPassword(password: CharArray, storedHashBase64: String, storedSaltBase64: String): Boolean {
        val salt = runCatching { decodeBase64(storedSaltBase64) }.getOrNull() ?: return false
        val expected = runCatching { decodeBase64(storedHashBase64) }.getOrNull() ?: return false
        val actual = pbkdf2(password, salt)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun generateEcKeyPair(): EcKeyPairEncoded {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val pair = generator.generateKeyPair()
        val pubB64 = encodeBase64(pair.public.encoded)
        val privB64 = encodeBase64(pair.private.encoded)
        return EcKeyPairEncoded(
            publicKeyBase64 = pubB64,
            privateKeyBase64 = privB64,
            fingerprint = computeFingerprint(pubB64)
        )
    }

    fun computeFingerprint(publicKeyBase64: String): String {
        val bytes = runCatching { decodeBase64(publicKeyBase64) }
            .getOrElse { publicKeyBase64.toByteArray(StandardCharsets.UTF_8) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    fun deriveSharedAesKey(privateKeyBase64: String, peerPublicKeyBase64: String): SecretKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val privBytes = decodeBase64(privateKeyBase64)
        val pubBytes = decodeBase64(peerPublicKeyBase64)
        val privateKey: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val publicKey: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun deriveChannelAesKey(channelPassphrase: String): SecretKey {
        val salted = "GRIDSIGHT_E2EE_CHANNEL_V1:$channelPassphrase".toByteArray(StandardCharsets.UTF_8)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(salted)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String, aesKey: SecretKey): EncryptedPayload {
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return EncryptedPayload(
            ciphertextBase64 = encodeBase64(encrypted),
            ivBase64 = encodeBase64(iv)
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, aesKey: SecretKey): String {
        val iv = decodeBase64(ivBase64)
        val ciphertext = decodeBase64(ciphertextBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun encodeBase64(input: ByteArray): String {
        val out = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val hasB1 = i + 1 < input.size
            val hasB2 = i + 2 < input.size
            val b1 = if (hasB1) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) input[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            out.append(B64_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(B64_ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) B64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) B64_ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    private fun decodeBase64(input: String): ByteArray {
        val clean = input.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        val padding = clean.takeLastWhile { it == '=' }.length
        val out = ByteArray((clean.length * 3) / 4 - padding)
        var outIdx = 0
        var i = 0
        while (i < clean.length) {
            val c0 = b64Val(clean[i])
            val c1 = b64Val(clean[i + 1])
            val c2 = if (clean[i + 2] == '=') 0 else b64Val(clean[i + 2])
            val c3 = if (clean[i + 3] == '=') 0 else b64Val(clean[i + 3])
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIdx < out.size) out[outIdx++] = ((triple ushr 16) and 0xFF).toByte()
            if (outIdx < out.size) out[outIdx++] = ((triple ushr 8) and 0xFF).toByte()
            if (outIdx < out.size) out[outIdx++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun b64Val(c: Char): Int {
        val idx = B64_ALPHABET.indexOf(c)
        require(idx >= 0) { "Invalid Base64 char: $c" }
        return idx
    }
}
