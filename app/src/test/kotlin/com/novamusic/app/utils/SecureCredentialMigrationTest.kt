package com.novamusic.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure-safety tests for the one-time plaintext -> encrypted credential
 * migration planner ([CredentialMigration]).
 *
 * These exercise the exact decision path used by
 * [SecureCredentialStore.migrateFromDataStore]: a plaintext value is only
 * cleared once its encrypted write reports success (which in production means
 * the write was committed and verified via a decrypt round-trip). A failed
 * write must leave the plaintext untouched and report the key as failed so it
 * is retried on the next app launch.
 */
class SecureCredentialMigrationTest {

    private val credentialNames =
        setOf(
            "innerTubeCookie",
            "spotify_sp_dc",
            "spotify_sp_key",
            "spotify_access_token",
            "spotify_access_token_expires_at",
            "discordToken",
            "lastfmSession",
            "listenbrainz_token",
        )

    private val plaintext =
        mapOf(
            "innerTubeCookie" to "SID=abc; HSID=def; SAPISID=xyz; SSID=123",
            "spotify_sp_dc" to "AQItGJWvYS7X",
            "spotify_sp_key" to "someSpKey",
            "spotify_access_token" to "BQD9token",
            "spotify_access_token_expires_at" to "1750000000000",
            "discordToken" to "NzUy_TOKEN",
            "lastfmSession" to "deadbeef-session",
            "listenbrainz_token" to "lb-token-42",
        )

    /** In-memory stand-in for the Keystore-backed store. */
    private class FakeProtectedStore {
        val values = mutableMapOf<String, String>()
        var failWritesFor: Set<String> = emptySet()

        fun read(name: String): String? = values[name]

        fun write(name: String, value: String): Boolean {
            if (name in failWritesFor) return false
            values[name] = value
            return true
        }
    }

    private fun runMigration(
        plaintext: Map<String, String> = this.plaintext,
        protected: FakeProtectedStore,
    ): CredentialMigration.Result =
        CredentialMigration.plan(
            credentialNames = credentialNames,
            readPlaintext = { name -> plaintext[name] },
            readProtected = protected::read,
            writeProtected = protected::write,
        )

    @Test
    fun `successful migration migrates every credential and clears all plaintext`() {
        val protected = FakeProtectedStore()

        val result = runMigration(protected = protected)

        assertEquals(credentialNames, result.cleared)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.anyAttempted)
        // Every credential actually landed in the protected store.
        plaintext.forEach { (name, value) ->
            assertEquals(value, protected.read(name))
        }
    }

    @Test
    fun `encryption failure keeps the plaintext credential intact`() {
        val protected = FakeProtectedStore().apply {
            // Simulate getOrCreateKey()/encrypt() returning null for this key.
            failWritesFor = setOf("innerTubeCookie")
        }

        val result = runMigration(protected = protected)

        assertTrue("innerTubeCookie" in result.failed)
        assertTrue("innerTubeCookie" !in result.cleared)
        // The failed key is excluded from the caller's clear-set, so its
        // plaintext source value survives and is retried next launch.
        assertFalse(result.cleared.contains("innerTubeCookie"))
        // Nothing was written to the protected store for the failed key.
        assertFalse(protected.values.containsKey("innerTubeCookie"))
    }

    @Test
    fun `failed write does not clear the failed key plaintext`() {
        val protected = FakeProtectedStore().apply {
            failWritesFor = setOf("discordToken")
        }

        val result = runMigration(protected = protected)

        assertEquals(setOf("discordToken"), result.failed)
        assertTrue(result.cleared.isEmpty().not())
        assertTrue("discordToken" !in result.cleared)
        assertFalse(protected.values.containsKey("discordToken"))
    }

    @Test
    fun `migration is idempotent across repeated launches`() {
        val protected = FakeProtectedStore()
        val mutablePlaintext = plaintext.toMutableMap()

        // First launch: migrates everything and clears the migrated plaintext,
        // mirroring what migrateFromDataStore() does after the plan succeeds.
        val first = runMigration(plaintext = mutablePlaintext, protected = protected)
        assertEquals(credentialNames, first.cleared)
        assertTrue(first.failed.isEmpty())
        first.cleared.forEach { mutablePlaintext.remove(it) }

        // Second launch: nothing left to migrate, nothing cleared, no failures.
        val second = runMigration(plaintext = mutablePlaintext, protected = protected)
        assertTrue(second.cleared.isEmpty())
        assertTrue(second.failed.isEmpty())
        assertFalse(second.anyAttempted)
    }

    @Test
    fun `partial failure migrates the good key but preserves the bad one for retry`() {
        val protected = FakeProtectedStore().apply {
            failWritesFor = setOf("spotify_sp_key", "lastfmSession")
        }

        val result = runMigration(protected = protected)

        // Successful keys get migrated and their plaintext cleared.
        assertTrue("spotify_sp_dc" in result.cleared)
        assertTrue("spotify_access_token" in result.cleared)
        assertEquals(plaintext["spotify_sp_dc"], protected.read("spotify_sp_dc"))

        // Failed keys are NOT cleared and have no protected copy yet.
        assertEquals(
            setOf("spotify_sp_key", "lastfmSession"),
            result.failed,
        )
        assertFalse(protected.values.containsKey("spotify_sp_key"))
        assertFalse(protected.values.containsKey("lastfmSession"))
    }

    @Test
    fun `already-migrated plaintext is pruned without rewriting`() {
        val protected = FakeProtectedStore()
        protected.write("innerTubeCookie", plaintext["innerTubeCookie"]!!)

        val result = runMigration(protected = protected)

        assertTrue("innerTubeCookie" in result.cleared)
        assertTrue(result.failed.isEmpty())
        // No rewrite happened: the protected value is unchanged.
        assertEquals(plaintext["innerTubeCookie"], protected.read("innerTubeCookie"))
    }
}