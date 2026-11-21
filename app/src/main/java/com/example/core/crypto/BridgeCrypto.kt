package com.example.core.crypto

import android.os.Build
import android.util.Base64
import com.example.domain.model.EncryptionCipher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides real AEAD framing (AES-256-GCM / ChaCha20-Poly1305) and HMAC-SHA256
 * handshake authentication for VeloBridge phone-to-phone encrypted tunnels.
 */
object BridgeCrypto {
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val MAX_FRAME_BYTES = 65536
    private val random = SecureRandom()

    fun deriveKey(uuidOrSecret: String, salt: String = "velobridge-v1-key"): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$uuidOrSecret::$salt".toByteArray(Charsets.UTF_8)
        val keyBytes = digest.digest(input)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun computeAuthToken(uuidOrSecret: String, disguiseDomain: String, timestampWindow: Long): String {
        val key = deriveKey(uuidOrSecret, "velobridge-auth-hmac").encoded
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val payload = "$disguiseDomain:$timestampWindow".toByteArray(Charsets.UTF_8)
        val sig = mac.doFinal(payload)
        return Base64.encodeToString(sig, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun verifyAuthToken(
        token: String,
        uuidOrSecret: String,
        disguiseDomain: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val window = nowSeconds / 60L
        for (delta in -2L..2L) {
            val expected = computeAuthToken(uuidOrSecret, disguiseDomain, window + delta)
            if (MessageDigest.isEqual(expected.toByteArray(), token.trim().toByteArray())) {
                return true
            }
        }
        return false
    }

    fun encryptFrame(
        plaintext: ByteArray,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val useChaCha = cipherMode == EncryptionCipher.CHACHA20_POLY1305 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val encrypted = if (useChaCha) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey.encoded, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(plaintext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(plaintext)
        }
        return ByteArray(NONCE_BYTES + encrypted.size).apply {
            System.arraycopy(nonce, 0, this, 0, NONCE_BYTES)
            System.arraycopy(encrypted, 0, this, NONCE_BYTES, encrypted.size)
        }
    }

    fun decryptFrame(
        frame: ByteArray,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher
    ): ByteArray {
        require(frame.size > NONCE_BYTES) { "Encrypted frame too short" }
        val nonce = frame.copyOfRange(0, NONCE_BYTES)
        val ciphertext = frame.copyOfRange(NONCE_BYTES, frame.size)
        val useChaCha = cipherMode == EncryptionCipher.CHACHA20_POLY1305 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return if (useChaCha) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey.encoded, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        }
    }

    fun writeEncryptedFrame(
        out: DataOutputStream,
        plaintext: ByteArray,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher
    ) {
        val encrypted = encryptFrame(plaintext, secretKey, cipherMode)
        synchronized(out) {
            out.writeInt(encrypted.size)
            out.write(encrypted)
            out.flush()
        }
    }

    fun readEncryptedFrame(
        input: DataInputStream,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher
    ): ByteArray? {
        val len = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw IllegalStateException("Invalid encrypted frame size: $len")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return decryptFrame(buf, secretKey, cipherMode)
    }
}
