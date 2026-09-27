/*
 * Regression tests for the logout bug: forgetAccount() used to clear only the
 * DataStore copy of the InnerTube cookie, while the credential itself had been
 * migrated into the Keystore-backed SecureCredentialStore — so the user stayed
 * signed in after logout.
 *
 * Platform note (same constraint as TranslationSettingsRepositoryTest): plain
 * Robolectric has no usable AndroidKeyStore, so SecureCredentialStore.encrypt()
 * is a no-op writer on the JVM. Where real crypto is unavailable, these tests
 * seed the backing SharedPreferences file directly to reproduce the
 * "credential exists in the secure store" state; the removal path under test
 * (SecureCredentialStore.remove) involves no crypto and works identically on
 * both platforms. Uses a fake cookie only; no real account, no network access.
 *
 * The Application under test is overridden to a plain android.app.Application:
 * the manifest's @HiltAndroidApp App would otherwise be booted by Robolectric,
 * and its background collectors (which regenerate visitorData via the network)
 * race the assertions below.
 */
package com.novamusic.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.constants.AccountChannelHandleKey
import com.novamusic.app.constants.AccountEmailKey
import com.novamusic.app.constants.AccountNameKey
import com.novamusic.app.constants.DataSyncIdKey
import com.novamusic.app.constants.InnerTubeCookieKey
import com.novamusic.app.constants.PoTokenKey
import com.novamusic.app.constants.VisitorDataKey
import com.novamusic.app.innertube.YouTube
import com.novamusic.app.utils.PreferenceStore
import com.novamusic.app.utils.SecureCredentialStore
import com.novamusic.app.utils.dataStore
import com.novamusic.app.utils.get
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForgetAccountLogoutTest {

    private lateinit var context: Context

    private companion object {
        /** Clearly fake — never a real session cookie. */
        const val TEST_COOKIE = "SAPISID=fake-test-sapisid; __Secure-1PAPISID=fake-test-sapisid"
        const val LEGACY_COOKIE = "SAPISID=legacy-plaintext-test; VISITOR_INFO1_LIVE=fake"
        const val TEST_VISITOR_DATA = "fake-visitor-data"
        const val TEST_DATA_SYNC_ID = "fake-datasync-id"

        /** Marker for the backing file when the JVM cannot encrypt (see class doc). */
        const val JVM_SEED_BLOB = "jvm-seed-blob-not-decryptable"

        /** Must match SecureCredentialStore.PREFS_NAME (private there). */
        const val SECURE_PREFS_NAME = "secure_credentials"

        /** Keys forgetAccount() is expected to clear from DataStore. */
        val accountDataStoreKeys = listOf(
            InnerTubeCookieKey,
            PoTokenKey,
            VisitorDataKey,
            DataSyncIdKey,
            AccountNameKey,
            AccountEmailKey,
            AccountChannelHandleKey,
        )
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SecureCredentialStore.init(context)
        PreferenceStore.start(context)
        // Start each test from a clean slate regardless of test order.
        SecureCredentialStore.remove(InnerTubeCookieKey.name)
        runBlocking {
            context.dataStore.edit { prefs ->
                accountDataStoreKeys.forEach { prefs.remove(it) }
            }
        }
        YouTube.authState = YouTube.authState.copy(cookie = null)
    }

    @After
    fun tearDown() {
        SecureCredentialStore.remove(InnerTubeCookieKey.name)
        runBlocking {
            context.dataStore.edit { prefs ->
                accountDataStoreKeys.forEach { prefs.remove(it) }
            }
        }
        YouTube.authState = YouTube.authState.copy(cookie = null)
    }

    /** forgetAccount() runs on its own IO scope; wait for its final effect. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(25)
        }
    }

    /**
     * True when this environment can actually encrypt/decrypt through the secure
     * store (device, or a Robolectric configured with a keystore provider). Probed
     * with a throwaway value that is removed again either way.
     */
    private fun secureStoreCanEncrypt(): Boolean {
        SecureCredentialStore.putString(InnerTubeCookieKey.name, "probe")
        val works = SecureCredentialStore.getString(InnerTubeCookieKey.name) == "probe"
        SecureCredentialStore.remove(InnerTubeCookieKey.name)
        return works
    }

    private fun rawStoredDataStoreCookie(): String? =
        runBlocking { context.dataStore.data.first()[InnerTubeCookieKey] }

    // ─────────────────────── the regression ───────────────────────

    /**
     * Post-migration state (cookie only in the secure store): logout must remove the
     * encrypted credential, clear the legacy DataStore keys, and reset the in-memory
     * auth state so requests go out unauthenticated.
     */
    @Test
    fun logoutRemovesTheMigratedEncryptedCookie() {
        val canEncrypt = secureStoreCanEncrypt()
        if (canEncrypt) {
            SecureCredentialStore.putString(InnerTubeCookieKey.name, TEST_COOKIE)
        } else {
            // No usable AndroidKeyStore on the JVM: reproduce the "migrated credential
            // present" state by writing the backing file directly.
            context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(InnerTubeCookieKey.name, JVM_SEED_BLOB)
                .commit()
        }
        runBlocking {
            context.dataStore.edit { prefs ->
                prefs[VisitorDataKey] = TEST_VISITOR_DATA
                prefs[DataSyncIdKey] = TEST_DATA_SYNC_ID
            }
        }
        YouTube.authState = YouTube.authState.copy(cookie = TEST_COOKIE, visitorData = TEST_VISITOR_DATA)

        // Precondition A: the credential is in the secure store, and reads resolve it
        // (on the JVM a non-decryptable blob reads as the default, which is fine).
        assertTrue(
            "precondition: secure store must hold the migrated cookie",
            SecureCredentialStore.contains(InnerTubeCookieKey.name),
        )
        if (canEncrypt) {
            assertEquals(
                "precondition: raw DataStore holds no plaintext after migration",
                null,
                rawStoredDataStoreCookie(),
            )
        }

        App.forgetAccount(context)

        // The authState reset is the last effect of forgetAccount(); once it lands,
        // every earlier effect has landed too.
        awaitUntil { YouTube.authState.cookie == null }

        // B: secure store no longer holds the credential.
        assertFalse(
            "logout must remove the encrypted InnerTube cookie",
            SecureCredentialStore.contains(InnerTubeCookieKey.name),
        )
        assertEquals(
            "secure value must read as empty after logout",
            "",
            SecureCredentialStore.getString(InnerTubeCookieKey.name),
        )
        // B: no legacy plaintext left in DataStore.
        for (key in accountDataStoreKeys) {
            val stored = runBlocking { context.dataStore.data.first()[key] }
            assertNull("logout must clear DataStore key ${key.name}", stored)
        }
        // B: the underlying DataStore file is cleared (polled — the write is async),
        // and only then does the composite getter resolve to the default. For
        // credential keys dataStore.get() reads the in-memory PreferenceStore
        // snapshot, so asserting it before the store settles could pass spuriously.
        awaitUntil { rawStoredDataStoreCookie() == null }
        awaitUntil { context.dataStore.get(InnerTubeCookieKey, "") == "" }
        // B: the application no longer considers the account authenticated —
        // a null in-memory cookie means InnerTube sends no cookie/SAPISIDHASH header.
        assertNull("in-memory YouTube auth must be unauthenticated", YouTube.authState.cookie)
        // Requirement 7: non-credential session context is kept in memory, not wiped
        // blindly (the app regenerates it; LoginScreen refreshes it per login).
        assertEquals(
            "visitorData is not a credential and must survive logout in memory",
            TEST_VISITOR_DATA,
            YouTube.authState.visitorData,
        )
    }

    /**
     * A cookie stored the old way (plaintext in DataStore) still migrates into the
     * secure store, and a logout after that migration removes it for good.
     */
    @Test
    fun legacyCookieStillMigratesAndLogoutRemovesIt() {
        val canEncrypt = secureStoreCanEncrypt()
        runBlocking {
            context.dataStore.edit { prefs -> prefs[InnerTubeCookieKey] = LEGACY_COOKIE }
        }

        runBlocking { SecureCredentialStore.migrateFromDataStore(context) }

        // In both platform modes the plaintext copy is removed from DataStore.
        assertNull(
            "migration must remove the plaintext copy from DataStore",
            rawStoredDataStoreCookie(),
        )
        if (canEncrypt) {
            assertTrue(
                "migration must store the legacy cookie in the secure store",
                SecureCredentialStore.contains(InnerTubeCookieKey.name),
            )
            assertEquals(
                "reads must resolve the migrated secure value",
                LEGACY_COOKIE,
                SecureCredentialStore.readSecureCredentialSync(InnerTubeCookieKey) ?: "",
            )
        }
        // Note (observed, pre-existing, out of scope): on a platform where encryption
        // fails, migrateFromDataStore() sets migratedAny even though putString was a
        // no-op, so the legacy plaintext is deleted without an encrypted copy being
        // stored — the user is silently logged out. Real devices have a working
        // keystore; flagging the broken-keystore data-loss path here for the record.

        App.forgetAccount(context)
        awaitUntil { YouTube.authState.cookie == null }

        assertFalse(
            "logout after migration must remove the secure credential",
            SecureCredentialStore.contains(InnerTubeCookieKey.name),
        )
        awaitUntil { rawStoredDataStoreCookie() == null }
        awaitUntil { context.dataStore.get(InnerTubeCookieKey, "") == "" }
        assertNull("in-memory YouTube auth must be unauthenticated", YouTube.authState.cookie)
    }

    // ─────────────────────── no-regression guard ───────────────────────

    /** A credential-less logout (fresh install) must not throw and must stay logged out. */
    @Test
    fun logoutWithNoStoredCredentialIsSafe() {
        assertFalse(SecureCredentialStore.contains(InnerTubeCookieKey.name))

        App.forgetAccount(context)
        awaitUntil { YouTube.authState.cookie == null }

        assertFalse(SecureCredentialStore.contains(InnerTubeCookieKey.name))
        awaitUntil { rawStoredDataStoreCookie() == null }
        awaitUntil { context.dataStore.get(InnerTubeCookieKey, "") == "" }
        assertNotNull("account state object must remain usable", YouTube.authState)
    }
}
