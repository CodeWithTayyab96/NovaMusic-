/*
 * NovaMusic — secure storage for provider credentials.
 *
 * Deliberately NOT Echo Music's approach. Echo stores the OpenRouter key with
 * `rememberPreference(OpenRouterApiKey, "")`, i.e. plain preferences — readable on a rooted
 * device and in any backup. NovaMusic stores only ciphertext here.
 *
 * Also avoids `androidx.security:security-crypto` (EncryptedSharedPreferences), which Google
 * has deprecated. This is a small, dependency-free wrapper over the Android Keystore.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts short secrets (API keys) with an AES-256-GCM key held in the Android
 * Keystore, so the plaintext never touches disk.
 *
 * The ciphertext is handed to whatever persistence layer the caller already uses; this class
 * deliberately does not store anything itself, which keeps it free of lifecycle concerns and
 * easy to test the encode/decode contract of.
 *
 * The key is generated on first use, is non-exportable (Keystore-backed), and is never logged.
 */
object SecureKeyStore {
    private const val TAG = "SecureKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "novamusic_provider_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Marks a value as "encrypted by this class", so legacy plaintext can be migrated. */
    private const val PREFIX = "enc:v1:"

    /**
     * Encrypts [plaintext]. Returns null for a blank input, so callers can store null rather
     * than an encryption of nothing.
     *
     * On any failure the plaintext is NOT returned — a broken keystore must never silently
     * degrade into storing the key in the clear.
     */
    fun encrypt(plaintext: String?): String? {
        if (plaintext.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // iv || ciphertext, base64 — the IV is not secret but must be unique per operation.
            PREFIX +
                Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Never log the value or the exception message verbatim; both can carry material.
            Log.e(TAG, "Failed to encrypt a credential (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Decrypts a value produced by [encrypt].
     *
     * A value without the [PREFIX] is treated as legacy plaintext and returned as-is, so an
     * existing stored key keeps working and can be re-saved encrypted on next write. A value
     * WITH the prefix that fails to decrypt returns null rather than garbage — a wrong or
     * rotated key must look like "not configured", not like a corrupted credential.
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        if (!stored.startsWith(PREFIX)) return stored // legacy plaintext
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt a stored credential (${e.javaClass.simpleName})")
            null
        }
    }

    /** True when [stored] looks like a value this class produced. */
    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    /**
     * Renders a secret for display without revealing any of it.
     *
     * Deliberately shows neither the head nor the tail: any real characters narrow a brute
     * force, and the user only needs to know that a key is configured and roughly how long it
     * is. Callers show this in the settings field; the real value is never rendered.
     */
    fun mask(secret: String?): String {
        if (secret.isNullOrBlank()) return ""
        return "•".repeat(secret.length.coerceAtMost(32))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        // No user-authentication requirement: translation must be able to run
                        // in the background without a prompt.
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }
        return generator.generateKey()
    }
}
