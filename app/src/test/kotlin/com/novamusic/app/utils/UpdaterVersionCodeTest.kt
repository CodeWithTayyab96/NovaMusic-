/*
 * Additional pre-release verification for the versionCode-stamped update path.
 *
 * The release workflow stamps `<!-- novamusic-version-code: N -->` into the release
 * notes and Updater.extractVersionCode() prefers that code over semver. These tests
 * pin the exact marker format the workflow writes and the comparison behaviour the
 * production release relies on (20 > 19 ⇒ update offered; older/missing marker ⇒ no
 * downgrade and semver fallback).
 */
package com.novamusic.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionCodeTest {
    /** Mirrors release-build.yml's stamp format exactly. */
    private fun stampedBody(versionCode: Int, versionName: String): String =
        "<!-- novamusic-version-code: $versionCode\n" +
            "novamusic-version-name: $versionName -->"

    private fun release(tag: String, body: String?) = ReleaseInfo(
        tagName = tag,
        name = tag.removePrefix("v"),
        body = body,
        publishedAt = "2026-09-27T00:00:00Z",
        htmlUrl = "https://example.com/$tag",
    )

    @Test
    fun extractVersionCode_readsTheWorkflowStampFormat() {
        val info = release("v2.0.15", stampedBody(20, "2.0.15"))
        assertEquals(20, Updater.extractVersionCode(info))
    }

    @Test
    fun extractVersionCode_returnsNullWithoutAStamp() {
        assertNull(Updater.extractVersionCode(release("v1.0.3", "old-style notes")))
        assertNull(Updater.extractVersionCode(release("v1.0.3", null)))
    }

    @Test
    fun extractVersionCode_ignoresNonPositiveValues() {
        assertNull(Updater.extractVersionCode(release("v0.0.1", stampedBody(0, "0.0.1"))))
        assertNull(Updater.extractVersionCode(release("v0.0.1", stampedBody(-5, "0.0.1"))))
    }

    @Test
    fun stampedVersionCodeTwentyBeatsInstalledNineteen() {
        // The exact production comparison for this release: installed 2.0.14 (code 19),
        // published 2.0.15 (code 20).
        val latest = release("v2.0.15", stampedBody(20, "2.0.15"))
        assertTrue(Updater.isUpdateAvailable(latest, "2.0.14", 19))
    }

    @Test
    fun stampedVersionCodeNeverOffersADowngrade() {
        val older = release("v2.0.13", stampedBody(18, "2.0.13"))
        assertFalse("an older stamped code must never be offered", Updater.isUpdateAvailable(older, "2.0.14", 19))

        val same = release("v2.0.14", stampedBody(19, "2.0.14"))
        assertFalse("the same code must not be re-offered", Updater.isUpdateAvailable(same, "2.0.14", 19))
    }

    @Test
    fun missingStampFallsBackToSemverNoDowngrade() {
        // A release whose notes predate the stamp must not become an upgrade for a newer
        // installed semver (the v1.0.3-over-2.0.14 regression).
        val unstamped = release("v1.0.3", "plain notes")
        assertFalse(Updater.isUpdateAvailable(unstamped, "2.0.14", 19))
    }
}
