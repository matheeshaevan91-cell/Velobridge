package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class VaultHealthState {
    VERIFIED_KEYSTORE,
    TEST_EPHEMERAL_FALLBACK,
    DEGRADED_KEY_UNAVAILABLE,
    DEGRADED_KEY_INVALIDATED
}

sealed class VaultProtectOutcome {
    data class Protected(val ciphertext: String) : VaultProtectOutcome()
    data class AlreadyProtected(val ciphertext: String) : VaultProtectOutcome()
    data object Empty : VaultProtectOutcome()
    data class Failed(val reason: String) : VaultProtectOutcome()
}

sealed class VaultDecodeOutcome {
    data class Decrypted(val plaintext: String) : VaultDecodeOutcome()
    data class LegacyPlaintext(val plaintext: String) : VaultDecodeOutcome()
    data object Empty : VaultDecodeOutcome()
    data class CorruptedOrInvalidated(val reason: String, val keyInvalidated: Boolean) : VaultDecodeOutcome()
}

/**
 * Protects local sensitive secrets (profile UUIDs/passwords, server secret, paired device secrets)
 * via Android Keystore AES-256-GCM.
 *
 * Security invariants:
 * - Never silently returns plaintext if encryption fails.
 * - Never silently discards or overwrites existing credentials when decryption fails.
 * - Distinguishes real Keystore backing from JVM test-only fallback and degraded key states.
 */
object SecureVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "velobridge_local_vault_key_v1"
    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    private val random = SecureRandom()

    @Volatile
    private var jvmTestKey: SecretKey? = null

    @Volatile
    private var lastKeyInvalidated: Boolean = false

    @Volatile
    private var lastFailureReason: String? = null

    fun isEncryptedValue(value: String): Boolean = value.startsWith(ENCRYPTED_PREFIX)

    fun isKeystoreHardwareAvailable(): Boolean {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            true
        }.getOrDefault(false)
    }

    fun getVaultHealth(): VaultHealthState {
        if (lastKeyInvalidated) return VaultHealthState.DEGRADED_KEY_INVALIDATED
        if (!isKeystoreHardwareAvailable()) {
            return if (isRobolectricOrJvmEnvironment()) {
                VaultHealthState.TEST_EPHEMERAL_FALLBACK
            } else {
                VaultHealthState.DEGRADED_KEY_UNAVAILABLE
            }
        }
        return when (val probe = protectSecretStrict("velobridge-vault-probe")) {
            is VaultProtectOutcome.Protected -> {
                when (unprotectSecretStrict(probe.ciphertext)) {
                    is VaultDecodeOutcome.Decrypted -> VaultHealthState.VERIFIED_KEYSTORE
                    is VaultDecodeOutcome.CorruptedOrInvalidated -> {
                        if (lastKeyInvalidated) VaultHealthState.DEGRADED_KEY_INVALIDATED
                        else VaultHealthState.DEGRADED_KEY_UNAVAILABLE
                    }
                    else -> VaultHealthState.DEGRADED_KEY_UNAVAILABLE
                }
            }
            else -> VaultHealthState.DEGRADED_KEY_UNAVAILABLE
        }
    }

    fun getLastFailureReason(): String? = lastFailureReason

    fun resetTestState() {
        lastKeyInvalidated = false
        lastFailureReason = null
    }

    fun protectSecretStrict(plaintext: String): VaultProtectOutcome {
        if (plaintext.isBlank()) return VaultProtectOutcome.Empty
        if (isEncryptedValue(plaintext)) return VaultProtectOutcome.AlreadyProtected(plaintext)

        return try {
            val key = acquireKey()
                ?: return VaultProtectOutcome.Failed("Android Keystore key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size).apply {
                System.arraycopy(iv, 0, this, 0, iv.size)
                System.arraycopy(ciphertext, 0, this, iv.size, ciphertext.size)
            }
            lastFailureReason = null
            VaultProtectOutcome.Protected(ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP))
        } catch (e: KeyPermanentlyInvalidatedException) {
            lastKeyInvalidated = true
            lastFailureReason = "Keystore key permanently invalidated"
            VaultProtectOutcome.Failed(lastFailureReason!!)
        } catch (t: Throwable) {
            lastFailureReason = t.localizedMessage ?: t::class.java.simpleName
            VaultProtectOutcome.Failed(lastFailureReason!!)
        }
    }

    fun unprotectSecretStrict(storedValue: String): VaultDecodeOutcome {
        if (storedValue.isBlank()) return VaultDecodeOutcome.Empty
        if (!isEncryptedValue(storedValue)) {
            return VaultDecodeOutcome.LegacyPlaintext(storedValue)
        }
        val payload = storedValue.removePrefix(ENCRYPTED_PREFIX)
        return try {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size <= NONCE_BYTES + 16) {
                return VaultDecodeOutcome.CorruptedOrInvalidated(
                    reason = "Vault ciphertext truncated or malformed",
                    keyInvalidated = false
                )
            }
            val iv = bytes.copyOfRange(0, NONCE_BYTES)
            val ciphertext = bytes.copyOfRange(NONCE_BYTES, bytes.size)
            val key = acquireKey()
                ?: return VaultDecodeOutcome.CorruptedOrInvalidated(
                    reason = "Keystore key unavailable",
                    keyInvalidated = false
                )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            VaultDecodeOutcome.Decrypted(plain)
        } catch (e: KeyPermanentlyInvalidatedException) {
            lastKeyInvalidated = true
            lastFailureReason = "Keystore key permanently invalidated"
            VaultDecodeOutcome.CorruptedOrInvalidated(lastFailureReason!!, keyInvalidated = true)
        } catch (e: UnrecoverableKeyException) {
            lastKeyInvalidated = true
            lastFailureReason = "Keystore key unrecoverable after restore/change"
            VaultDecodeOutcome.CorruptedOrInvalidated(lastFailureReason!!, keyInvalidated = true)
        } catch (e: AEADBadTagException) {
            lastFailureReason = "Ciphertext authentication tag mismatch"
            VaultDecodeOutcome.CorruptedOrInvalidated(lastFailureReason!!, keyInvalidated = false)
        } catch (t: Throwable) {
            val reason = t.localizedMessage ?: t::class.java.simpleName
            lastFailureReason = reason
            VaultDecodeOutcome.CorruptedOrInvalidated(reason, keyInvalidated = false)
        }
    }

    /**
     * Convenience helper for non-throwing read paths; returns empty string if decryption failed
     * so corrupted credentials are never treated as valid secrets.
     */
    fun unprotectSecret(storedValue: String): String {
        return when (val res = unprotectSecretStrict(storedValue)) {
            is VaultDecodeOutcome.Decrypted -> res.plaintext
            is VaultDecodeOutcome.LegacyPlaintext -> res.plaintext
            is VaultDecodeOutcome.Empty -> ""
            is VaultDecodeOutcome.CorruptedOrInvalidated -> ""
        }
    }

    /**
     * Convenience helper for tests/legacy callers; returns null if encryption failed rather
     * than silently returning unencrypted plaintext.
     */
    fun protectSecret(plaintext: String): String? {
        return when (val res = protectSecretStrict(plaintext)) {
            is VaultProtectOutcome.Protected -> res.ciphertext
            is VaultProtectOutcome.AlreadyProtected -> res.ciphertext
            is VaultProtectOutcome.Empty -> ""
            is VaultProtectOutcome.Failed -> null
        }
    }

    private fun acquireKey(): SecretKey? {
        if (isKeystoreHardwareAvailable()) {
            return runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
                ks.load(null)
                if (ks.containsAlias(KEY_ALIAS)) {
                    (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                } else {
                    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    generator.init(spec)
                    generator.generateKey()
                }
            }.onFailure { err ->
                if (err is KeyPermanentlyInvalidatedException || err is UnrecoverableKeyException) {
                    lastKeyInvalidated = true
                }
                lastFailureReason = err.localizedMessage ?: err::class.java.simpleName
            }.getOrNull()
        }

        if (isRobolectricOrJvmEnvironment()) {
            return jvmTestKey ?: synchronized(this) {
                jvmTestKey ?: ByteArray(32).let { raw ->
                    random.nextBytes(raw)
                    SecretKeySpec(raw, "AES").also { jvmTestKey = it }
                }
            }
        }
        return null
    }

    private fun isRobolectricOrJvmEnvironment(): Boolean {
        val fingerprint = runCatching { android.os.Build.FINGERPRINT }.getOrDefault("")
        val runtime = System.getProperty("java.runtime.name").orEmpty()
        return fingerprint.equals("robolectric", ignoreCase = true) ||
            runtime.contains("OpenJDK", ignoreCase = true) ||
            runtime.contains("Java", ignoreCase = true) && !isKeystoreHardwareAvailable() &&
            runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
    }
}
