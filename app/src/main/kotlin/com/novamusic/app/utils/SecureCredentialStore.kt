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

    /**
     * Encrypts and persists [value] under [key], returning true only when the
     * credential was written to disk AND a decrypt round-trip verified it.
     *
     * Returns false (leaving any existing state untouched) if encryption, the
     * write, or the verification fails. Callers — in particular the one-time
     * DataStore migration — rely on this to never treat a plaintext credential
     * as migrated unless the encrypted copy is provably in place.
     */
    fun putString(key: String, value: String): Boolean {
        if (value.isEmpty()) return remove(key)
        val encrypted = encrypt(value) ?: return false
        val committed =
            runCatching { prefs?.edit()?.putString(key, encrypted)?.commit() }
                .getOrNull() == true
        if (!committed) return false
        return getString(key) == value
    }

    fun getString(key: String, defaultValue: String = ""): String {
        val blob = prefs?.getString(key, null) ?: return defaultValue
        return decrypt(blob) ?: defaultValue
    }

    fun putLong(key: String, value: Long): Boolean = putString(key, value.toString())

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        getString(key).toLongOrNull() ?: defaultValue

    fun remove(key: String): Boolean = runCatching {
        prefs?.edit()?.remove(key)?.commit() == true
    }.getOrDefault(false)

    fun contains(key: String): Boolean = prefs?.contains(key) == true

    // ---------------------------------------------------------------------
    // One-time migration from the plaintext DataStore
    // ---------------------------------------------------------------------

    /**
     * Copies any existing plaintext credential values out of the app's DataStore
     * into this encrypted store, then deletes the plaintext copies.
     *
     * Failure-safe: a plaintext value is only removed once its encrypted copy was
     * successfully written and verified (see [putString]). Any key that cannot be
     * migrated is left untouched and retried on the next app launch, so a
     * partially migrated account stays recoverable and no credential is lost.
     *
     * Idempotent: values already present in this store are never overwritten, and
     * plaintext that has a matching (or fresher) protected value is simply pruned.
     *
     * @return true when every credential is now under encrypted storage; false when
     * at least one plaintext value had to be preserved because its write failed.
     */
    suspend fun migrateFromDataStore(context: Context): Boolean {
        val dataStore = context.dataStore
        val snapshot = runCatching { dataStore.data.first() }.getOrNull() ?: return false

        val result =
            CredentialMigration.plan(
                credentialNames = credentialKeyNames,
                readPlaintext = { name ->
                    if (name == SpotifyAccessTokenExpiresAtKey.name) {
                        snapshot[longPreferencesKey(name)]?.toString()
                    } else {
                        snapshot[stringPreferencesKey(name)]
                    }
                },
                readProtected = { name ->
                    getString(name).takeIf { it.isNotEmpty() }
                },
                writeProtected = { name, value -> putString(name, value) },
            )

        if (result.cleared.isNotEmpty()) {
            runCatching {
                dataStore.edit { prefs ->
                    result.cleared.forEach { name ->
                        prefs.remove(stringPreferencesKey(name))
                        prefs.remove(longPreferencesKey(name))
                    }
                }
            }.onFailure { Log.e(TAG, "Failed to clear migrated plaintext credentials", it) }
        }

        if (result.failed.isNotEmpty()) {
            Log.e(TAG, "Migration could not encrypt these credentials; plaintext preserved: ${result.failed}")
        }

        return result.failed.isEmpty()
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

/**
 * Pure, testable decision logic for the one-time plaintext-to-encrypted
 * credential migration.
 *
 * For each credential name that has a non-blank plaintext value it decides
 * whether the plaintext is safe to clear:
 *  - the protected store already holds exactly the same value (already migrated),
 *  - the protected store holds a different, fresher value (old plaintext stale),
 *  - the protected write succeeded and was verified ([writeProtected] == true).
 *
 * A plaintext value is NEVER cleared when its encrypted write fails; it is
 * reported in [Result.failed] so the caller can log it and retry on the next
 * launch.
 */
internal object CredentialMigration {
    data class Result(
        /** Keys whose plaintext was verified as migrated and may be cleared. */
        val cleared: Set<String>,
        /** Keys whose encrypted write failed; plaintext must be preserved. */
        val failed: Set<String>,
        /** Whether any plaintext credential was found at all. */
        val anyAttempted: Boolean,
    )

    fun plan(
        credentialNames: Set<String>,
        readPlaintext: (String) -> String?,
        readProtected: (String) -> String?,
        writeProtected: (String, String) -> Boolean,
    ): Result {
        val cleared = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        var anyAttempted = false

        for (name in credentialNames) {
            val plain = readPlaintext(name)?.takeIf { it.isNotBlank() } ?: continue
            anyAttempted = true

            val protectedValue = readProtected(name)
            when {
                protectedValue == plain -> cleared += name
                protectedValue != null && protectedValue.isNotEmpty() -> cleared += name
                writeProtected(name, plain) -> cleared += name
                else -> failed += name
            }
        }

        return Result(cleared = cleared, failed = failed, anyAttempted = anyAttempted)
    }
}
