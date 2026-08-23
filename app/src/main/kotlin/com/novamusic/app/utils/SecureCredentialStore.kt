/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novamusic.app.constants.DiscordTokenKey
import com.novamusic.app.constants.InnerTubeCookieKey
import com.novamusic.app.constants.LastFMSessionKey
import com.novamusic.app.constants.ListenBrainzTokenKey
import com.novamusic.app.constants.SpotifyAccessTokenExpiresAtKey
import com.novamusic.app.constants.SpotifyAccessTokenKey
import com.novamusic.app.constants.SpotifySpDcKey
import com.novamusic.app.constants.SpotifySpKeyKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed encrypted storage for sensitive credentials.
 *
 * Values are AES-256-GCM encrypted with a key stored in the Android Keystore
 * (never exported, hardware-backed where available). Credentials stored here
 * (YouTube session cookie, Spotify cookies/tokens, Discord token, Last.fm
 * session, ListenBrainz token) are therefore NOT readable by:
 *  - app backups / device-to-device transfer (the backing SharedPreferences
 *    file is also excluded from backup — see backup_rules.xml), or
 *  - plain file reads from a compromised/rooted device (ciphertext only).
 *
 * Existing plaintext values living in the app's DataStore are migrated into
 * this store once at startup ([migrateFromDataStore]); the plaintext copies
 * are then deleted, so already-logged-in users stay logged in.
 */
object SecureCredentialStore {
    private const val TAG = "SecureCredentialStore"
    private const val PREFS_NAME = "secure_credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "novamusic_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12 // 96-bit GCM IV
    private const val TAG_BITS = 128

    /**
     * Preference-key names whose values are credentials and must be encrypted
     * at rest. Every key here is routed through this store by the DataStore
     * extension operators in DataStore.kt and by the migration below.
     *
     * PO tokens (PoTokenKey/Gvs/Player) are deliberately NOT included: they are
     * anti-bot session tokens already transmitted in cleartext inside every
     * InnerTube request, so encrypting them at rest adds no real protection.
     */
    val credentialKeyNames: Set<String> = setOf(
        InnerTubeCookieKey.name,
        SpotifySpDcKey.name,
        SpotifySpKeyKey.name,
        SpotifyAccessTokenKey.name,
        SpotifyAccessTokenExpiresAtKey.name,
        DiscordTokenKey.name,
        LastFMSessionKey.name,
        ListenBrainzTokenKey.name,
    )

    fun isCredential(keyName: String): Boolean = keyName in credentialKeyNames

    /** Name of the only non-String credential key (a Long timestamp). */
    val expiresAtKeyName: String = SpotifyAccessTokenExpiresAtKey.name

    @Volatile
    private var appContext: Context? = null

    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------------------------------------------------------------------
    // Encryption primitives
    // ---------------------------------------------------------------------

    private fun getOrCreateKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to create/load Android Keystore key", t)
        null
    }

    private fun encrypt(plaintext: String): String? = try {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.e(TAG, "Encryption failed", t)
        null
    }

    private fun decrypt(blob: String): String? = try {
        val key = getOrCreateKey() ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
        String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
    } catch (t: Throwable) {
        // Decryption failures are logged but treated as "no value": the user is
        // simply asked to log in again instead of the app crashing. This can
        // happen when a restore brings over a ciphertext whose Keystore key no
        // longer exists on this device.
        Log.e(TAG, "Decryption failed for a credential (it will be reset)", t)
        null
    }

    // ---------------------------------------------------------------------
    // Typed API
    // ---------------------------------------------------------------------

    fun putString(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        val encrypted = encrypt(value) ?: return
        runCatching { prefs?.edit()?.putString(key, encrypted)?.apply() }
    }

    fun getString(key: String, defaultValue: String = ""): String {
        val blob = prefs?.getString(key, null) ?: return defaultValue
        return decrypt(blob) ?: defaultValue
    }

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        getString(key).toLongOrNull() ?: defaultValue

    fun remove(key: String) {
        runCatching { prefs?.edit()?.remove(key)?.apply() }
    }

    fun contains(key: String): Boolean = prefs?.contains(key) == true

    // ---------------------------------------------------------------------
    // One-time migration from the plaintext DataStore
    // ---------------------------------------------------------------------

    /**
     * Copies any existing plaintext credential values out of the app's DataStore
     * into this encrypted store, then deletes the plaintext copies. Safe to run
     * repeatedly: values already present here are never overwritten.
     */
    suspend fun migrateFromDataStore(context: Context) {
        val dataStore = context.dataStore
        val snapshot = runCatching { dataStore.data.first() }.getOrNull() ?: return
        var migratedAny = false

        for (name in credentialKeyNames) {
            if (contains(name)) continue
            val plain =
                if (name == SpotifyAccessTokenExpiresAtKey.name) {
                    snapshot[longPreferencesKey(name)]?.toString()
                } else {
                    snapshot[stringPreferencesKey(name)]
                }
            if (plain.isNullOrBlank()) continue
            putString(name, plain)
            migratedAny = true
        }

        if (migratedAny) {
            runCatching {
                dataStore.edit { prefs ->
                    credentialKeyNames.forEach { name ->
                        prefs.remove(stringPreferencesKey(name))
                        prefs.remove(longPreferencesKey(name))
                    }
                }
            }.onFailure { Log.e(TAG, "Failed to clear migrated plaintext credentials", it) }
        }
    }

    /**
     * Synchronous typed read through the encrypted store. Returns null when the
     * credential is absent (callers fall back to the legacy in-memory DataStore
     * snapshot while the one-time migration has not yet run).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> readSecureCredentialSync(key: Preferences.Key<T>): T? =
        if (key.name == expiresAtKeyName) {
            getLong(key.name).takeIf { it != 0L } as T?
        } else {
            getString(key.name).takeIf { it.isNotEmpty() } as T?
        }
}
