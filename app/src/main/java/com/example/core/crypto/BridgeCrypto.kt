package com.example.core.crypto

import android.util.Base64
import com.example.domain.model.EncryptionCipher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides RFC 5869 HKDF-SHA256 domain-separated key derivation, P-256 ECDH ephemeral key exchange,
 * AEAD framing (AES-256-GCM / ChaCha20-Poly1305), and nonce-enforced replay-guarded handshake auth.
 */
object BridgeCrypto {
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val AEAD_TAG_BYTES = 16
    private const val MIN_AEAD_FRAME_BYTES = NONCE_BYTES + AEAD_TAG_BYTES
    private const val MAX_FRAME_BYTES = 65536
    private const val REPLAY_TTL_SECONDS = 300L
    private const val MAX_REPLAY_ENTRIES = 2048
    private const val MIN_NONCE_CHARS = 12

    private val random = SecureRandom()
    private val seenAuthNonces = LinkedHashMap<String, Long>(256, 0.75f, false)

    @Volatile
    private var replayEnforcementVerified = true

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        lengthBytes: Int = 32
    ): ByteArray {
        require(lengthBytes in 1..(255 * 32)) { "Invalid HKDF length: $lengthBytes" }
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(lengthBytes)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < lengthBytes) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, lengthBytes - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    /**
     * Domain-separated static fallback key when no ephemeral ECDH exchange is present.
     */
    fun deriveKey(uuidOrSecret: String, salt: String = "velobridge-v2-key"): SecretKey {
        val keyBytes = hkdfSha256(
            ikm = uuidOrSecret.toByteArray(Charsets.UTF_8),
            salt = salt.toByteArray(Charsets.UTF_8),
            info = "velobridge-aead-session-v2".toByteArray(Charsets.UTF_8),
            lengthBytes = 32
        )
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Domain-separated key exclusively for HMAC-SHA256 handshake authentication.
     */
    fun deriveAuthMacKey(uuidOrSecret: String): ByteArray {
        return hkdfSha256(
            ikm = uuidOrSecret.toByteArray(Charsets.UTF_8),
            salt = "velobridge-auth-salt-v2".toByteArray(Charsets.UTF_8),
            info = "velobridge-auth-hmac-v2".toByteArray(Charsets.UTF_8),
            lengthBytes = 32
        )
    }

    /**
     * Ephemeral forward-secret session key combining pre-shared secret, P-256 ECDH shared secret,
     * and both client + server nonces with cipher-specific domain separation.
     */
    fun deriveEcdhSessionKey(
        uuidOrSecret: String,
        ecdhSharedSecret: ByteArray,
        clientNonce: String,
        serverNonce: String,
        cipher: EncryptionCipher
    ): SecretKey {
        val ikm = uuidOrSecret.toByteArray(Charsets.UTF_8) + ecdhSharedSecret
        val salt = "$clientNonce:$serverNonce".toByteArray(Charsets.UTF_8)
        val info = "velobridge-ecdh-frame-${cipher.wireValue}-v2".toByteArray(Charsets.UTF_8)
        val keyBytes = hkdfSha256(ikm = ikm, salt = salt, info = info, lengthBytes = 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    fun decodePublicKey(encodedB64: String): PublicKey {
        val bytes = Base64.decode(encodedB64.trim(), Base64.URL_SAFE)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKeyB64: String): ByteArray {
        val peerPub = decodePublicKey(peerPublicKeyB64)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    fun derivePairingKey(
        ecdhSharedSecret: ByteArray,
        clientNonce: String,
        serverNonce: String
    ): SecretKey {
        val keyBytes = hkdfSha256(
            ikm = ecdhSharedSecret,
            salt = "$clientNonce:$serverNonce".toByteArray(Charsets.UTF_8),
            info = "velobridge-lan-pair-aead-v1".toByteArray(Charsets.UTF_8),
            lengthBytes = 32
        )
        return SecretKeySpec(keyBytes, "AES")
    }

    fun computeSasCode(
        ecdhSharedSecret: ByteArray,
        clientNonce: String,
        serverNonce: String
    ): String {
        val digestBytes = hkdfSha256(
            ikm = ecdhSharedSecret,
            salt = "$clientNonce:$serverNonce".toByteArray(Charsets.UTF_8),
            info = "velobridge-lan-pair-sas-v1".toByteArray(Charsets.UTF_8),
            lengthBytes = 4
        )
        val raw = ((digestBytes[0].toInt() and 0x7F) shl 24) or
            ((digestBytes[1].toInt() and 0xFF) shl 16) or
            ((digestBytes[2].toInt() and 0xFF) shl 8) or
            (digestBytes[3].toInt() and 0xFF)
        return (raw % 1_000_000).toString().padStart(6, '0')
    }

    fun sha256Fingerprint(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun generateAuthNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun computeAuthToken(
        uuidOrSecret: String,
        disguiseDomain: String,
        timestampWindow: Long
    ): String = computeAuthToken(uuidOrSecret, disguiseDomain, timestampWindow, "")

    fun computeAuthToken(
        uuidOrSecret: String,
        disguiseDomain: String,
        timestampWindow: Long,
        nonce: String,
        clientEcdhPubB64: String = ""
    ): String {
        val key = deriveAuthMacKey(uuidOrSecret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val payloadStr = buildString {
            append(disguiseDomain.trim().lowercase())
            append(':')
            append(timestampWindow)
            if (nonce.isNotBlank()) {
                append(':').append(nonce.trim())
            }
            if (clientEcdhPubB64.isNotBlank()) {
                append(':').append(clientEcdhPubB64.trim())
            }
        }
        val sig = mac.doFinal(payloadStr.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun computeServerAuthAck(
        uuidOrSecret: String,
        disguiseDomain: String,
        clientNonce: String,
        serverNonce: String,
        serverEcdhPubB64: String
    ): String {
        val key = deriveAuthMacKey(uuidOrSecret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val payload = "SERVER_ACK:${disguiseDomain.trim().lowercase()}:$clientNonce:$serverNonce:$serverEcdhPubB64"
        val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun verifyServerAuthAck(
        serverAck: String,
        uuidOrSecret: String,
        disguiseDomain: String,
        clientNonce: String,
        serverNonce: String,
        serverEcdhPubB64: String
    ): Boolean {
        if (serverAck.isBlank() || serverNonce.isBlank()) return false
        val expected = computeServerAuthAck(
            uuidOrSecret = uuidOrSecret,
            disguiseDomain = disguiseDomain,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            serverEcdhPubB64 = serverEcdhPubB64
        )
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            serverAck.trim().toByteArray(Charsets.UTF_8)
        )
    }

    fun verifyAuthTokenWithReplayCheck(
        token: String,
        uuidOrSecret: String,
        disguiseDomain: String,
        nonce: String?,
        clientEcdhPubB64: String = "",
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val cleanNonce = nonce?.trim().orEmpty()
        val cleanToken = token.trim()
        if (cleanToken.isBlank() || uuidOrSecret.isBlank()) return false
        // Reject stripped/missing/short nonces so replay protection can never be bypassed
        if (cleanNonce.length < MIN_NONCE_CHARS) return false

        val window = nowSeconds / 60L
        var matchedWindow: Long? = null
        for (delta in -2L..2L) {
            val expected = computeAuthToken(
                uuidOrSecret = uuidOrSecret,
                disguiseDomain = disguiseDomain,
                timestampWindow = window + delta,
                nonce = cleanNonce,
                clientEcdhPubB64 = clientEcdhPubB64
            )
            if (MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8),
                    cleanToken.toByteArray(Charsets.UTF_8)
                )
            ) {
                matchedWindow = window + delta
                break
            }
        }
        if (matchedWindow == null) return false

        val replayKey = "${disguiseDomain.trim().lowercase()}|$matchedWindow|$cleanNonce"
        synchronized(seenAuthNonces) {
            pruneExpiredNoncesLocked(nowSeconds)
            if (seenAuthNonces.containsKey(replayKey)) {
                return false
            }
            seenAuthNonces[replayKey] = nowSeconds + REPLAY_TTL_SECONDS
        }
        return true
    }

    fun isReplayProtectionOperational(): Boolean {
        return synchronized(seenAuthNonces) {
            replayEnforcementVerified && seenAuthNonces.size <= MAX_REPLAY_ENTRIES
        }
    }

    fun clearReplayCacheForTesting() {
        synchronized(seenAuthNonces) {
            seenAuthNonces.clear()
        }
    }

    private fun pruneExpiredNoncesLocked(nowSeconds: Long) {
        val iterator = seenAuthNonces.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < nowSeconds) {
                iterator.remove()
            }
        }
        while (seenAuthNonces.size >= MAX_REPLAY_ENTRIES) {
            val oldestKey = seenAuthNonces.keys.firstOrNull() ?: break
            seenAuthNonces.remove(oldestKey)
        }
    }

    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return "Not set"
        if (trimmed.length <= 8) return "••••••••"
        return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
    }

    fun isChaCha20Supported(): Boolean {
        return runCatching {
            Cipher.getInstance("ChaCha20-Poly1305")
            true
        }.getOrDefault(false)
    }

    fun resolveEffectiveCipher(requested: EncryptionCipher): EncryptionCipher {
        return when (requested) {
            EncryptionCipher.CHACHA20_POLY1305 ->
                if (isChaCha20Supported()) EncryptionCipher.CHACHA20_POLY1305 else EncryptionCipher.AES_256_GCM
            EncryptionCipher.AUTO -> EncryptionCipher.AES_256_GCM
            EncryptionCipher.AES_256_GCM -> EncryptionCipher.AES_256_GCM
            EncryptionCipher.NONE -> EncryptionCipher.AES_256_GCM
        }
    }

    fun encryptFrame(
        plaintext: ByteArray,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher,
        aad: ByteArray? = null
    ): ByteArray {
        val effective = resolveEffectiveCipher(cipherMode)
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val encrypted = if (effective == EncryptionCipher.CHACHA20_POLY1305) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey.encoded, "ChaCha20"), IvParameterSpec(nonce))
            if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
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
        cipherMode: EncryptionCipher,
        aad: ByteArray? = null
    ): ByteArray {
        require(frame.size >= MIN_AEAD_FRAME_BYTES) { "Encrypted frame too short (${frame.size} bytes)" }
        val effective = resolveEffectiveCipher(cipherMode)
        val nonce = frame.copyOfRange(0, NONCE_BYTES)
        val ciphertext = frame.copyOfRange(NONCE_BYTES, frame.size)
        return if (effective == EncryptionCipher.CHACHA20_POLY1305) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey.encoded, "ChaCha20"), IvParameterSpec(nonce))
            if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }
    }

    fun writeEncryptedFrame(
        out: DataOutputStream,
        plaintext: ByteArray,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher,
        aad: ByteArray? = null
    ) {
        val encrypted = encryptFrame(plaintext, secretKey, cipherMode, aad)
        require(encrypted.size <= MAX_FRAME_BYTES) { "Encrypted frame exceeds max frame size" }
        synchronized(out) {
            out.writeInt(encrypted.size)
            out.write(encrypted)
            out.flush()
        }
    }

    fun readEncryptedFrame(
        input: DataInputStream,
        secretKey: SecretKey,
        cipherMode: EncryptionCipher,
        aad: ByteArray? = null
    ): ByteArray? {
        val len = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (len < MIN_AEAD_FRAME_BYTES || len > MAX_FRAME_BYTES) {
            throw IllegalStateException("Invalid encrypted frame size: $len")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return decryptFrame(buf, secretKey, cipherMode, aad)
    }
}
