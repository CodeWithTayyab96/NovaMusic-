package com.novamusic.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterSemVerTest {
    @Test
    fun findLatestRelease_picksHighestStableSemverEvenIfNotFirst() {
        val releases =
            listOf(
                ReleaseInfo(
                    tagName = "v12.4.7",
                    name = "12.4.7",
                    body = null,
                    publishedAt = "2026-01-01T00:00:00Z",
                    htmlUrl = "https://example.com/12.4.7",
                ),
                ReleaseInfo(
                    tagName = "v13.0.0",
                    name = "13.0.0",
                    body = null,
                    publishedAt = "2026-02-01T00:00:00Z",
                    htmlUrl = "https://example.com/13.0.0",
                ),
            )

        val latest = Updater.findLatestRelease(releases)
        assertNotNull(latest)
        assertEquals("v13.0.0", latest?.tagName)
    }

    @Test
    fun findLatestRelease_ignoresPrereleaseWhenStableExists() {
        val releases =
            listOf(
                ReleaseInfo(
                    tagName = "v13.0.0-beta.1",
                    name = "13.0.0-beta.1",
                    body = null,
                    publishedAt = "2026-02-01T00:00:00Z",
                    htmlUrl = "https://example.com/13.0.0-beta.1",
                ),
                ReleaseInfo(
                    tagName = "v12.4.7",
                    name = "12.4.7",
                    body = null,
                    publishedAt = "2026-01-01T00:00:00Z",
                    htmlUrl = "https://example.com/12.4.7",
                ),
            )

        val latest = Updater.findLatestRelease(releases)
        assertNotNull(latest)
        assertEquals("v12.4.7", latest?.tagName)
    }

    @Test
    fun isSameVersion_matchesSemverRegardlessOfPrefixOrText() {
        assertTrue(Updater.isSameVersion("v13.0.0", "13.0.0"))
        assertTrue(Updater.isSameVersion("OpenTune 13.0.0", "13.0.0"))
        assertFalse(Updater.isSameVersion("13.0.1", "13.0.0"))
    }

    // ───────────────────────────────────────────────────────────────────────
    // Regression: the updater used to treat "latest != current" as an update,
    // so a published release OLDER than the installed build was offered as an
    // upgrade. These tests lock the "strictly newer" rule down.
    // ───────────────────────────────────────────────────────────────────────

    private fun release(
        tag: String,
        name: String = tag,
        body: String? = null,
    ) = ReleaseInfo(
        tagName = tag,
        name = name,
        body = body,
        publishedAt = "2026-01-01T00:00:00Z",
        htmlUrl = "https://example.com/$tag",
    )

    @Test
    fun isUpdateAvailable_matrix() {
        // installed → published, expected
        val cases =
            listOf(
                Triple("1.0.3", "2.0.1", true),   // older installed, newer published
                Triple("2.0.0", "2.0.1", true),   // patch bump
                Triple("2.0.1", "2.0.1", false),  // identical
                Triple("2.0.1", "2.0.0", false),  // published is older (patch)
                Triple("2.0.1", "1.0.3", false),  // published is older (minor/major)
                Triple("1.9.9", "2.0.0", true),   // minor rollover
                Triple("2.1.0", "2.0.9", false),  // published is older minor
                Triple("1.0.0", "3.0.0", true),   // major difference
                Triple("3.0.0", "1.0.0", false),  // major downgrade
                Triple("2.0.0", "2.1.0", true),   // minor difference
                Triple("2.1.0", "2.0.0", false),  // minor downgrade
            )

        cases.forEach { (installed, published, expected) ->
            val actual =
                Updater.isUpdateAvailable(
                    latest = release("v$published", published),
                    currentVersionName = installed,
                    currentVersionCode = 0,
                )
            assertEquals(
                "installed=$installed published=$published expected=$expected",
                expected,
                actual,
            )
        }
    }

    @Test
    fun isUpdateAvailable_liveScenario_published103WhileAppIs200_reportsNoUpdate() {
        // The exact production situation: v1.0.3 was the newest GitHub Release while
        // the app shipped 2.0.0. This must NOT be reported as an update.
        val latest = release("v1.0.3", "1.0.3")
        assertFalse(Updater.isUpdateAvailable(latest, "2.0.0", 5))
    }

    @Test
    fun isUpdateAvailable_afterPublishing201_reportsUpdate() {
        val latest = release("v2.0.1", "2.0.1")
        assertTrue(Updater.isUpdateAvailable(latest, "2.0.0", 5))
    }

    @Test
    fun isUpdateAvailable_neverOffersDowngradeAcrossFullMatrix() {
        val installed = listOf("1.0.0", "1.0.3", "2.0.0", "2.0.1", "3.0.0")
        val published = listOf("1.0.0", "1.0.3", "2.0.0", "2.0.1", "3.0.0")

        installed.forEach { current ->
            published.forEach { candidate ->
                if (candidate <= current) {
                    assertFalse(
                        "published $candidate must not be offered over installed $current",
                        Updater.isUpdateAvailable(release("v$candidate", candidate), current, 0),
                    )
                }
            }
        }
    }

    @Test
    fun isNewerSemVer_handlesMalformedVersionsByFailingClosed() {
        assertFalse(Updater.isNewerSemVer("garbage", "2.0.1"))
        assertFalse(Updater.isNewerSemVer("2.0.1", "garbage"))
        assertFalse(Updater.isNewerSemVer("", ""))
        assertFalse(Updater.isNewerSemVer("not.a.version", "also.bad"))
        // A newer but unparseable candidate must still not be offered.
        assertFalse(Updater.isNewerSemVer("nightly", "2.0.1"))
    }

    @Test
    fun isNewerSemVer_handlesPrereleaseOrdering() {
        // stable beats its own prerelease
        assertFalse(Updater.isNewerSemVer("2.0.1-beta.1", "2.0.1"))
        assertTrue(Updater.isNewerSemVer("2.0.1", "2.0.1-beta.1"))
        // a prerelease of a higher version is still newer than the current stable
        assertTrue(Updater.isNewerSemVer("2.0.1-beta.1", "2.0.0"))
        assertFalse(Updater.isNewerSemVer("2.0.0-beta.1", "2.0.0"))
        // numeric prerelease identifiers compare numerically, not lexically
        assertTrue(Updater.isNewerSemVer("2.0.1-beta.10", "2.0.1-beta.2"))
    }

    // ─── versionCode is the authoritative comparison when present ───────────

    @Test
    fun extractVersionCode_readsReleaseMarker() {
        assertEquals(6, Updater.extractVersionCode(release("v2.0.1", "2.0.1", "novamusic-version-code: 6")))
        assertEquals(6, Updater.extractVersionCode(release("v2.0.1", "2.0.1", "notes\nNovamusic-Version-Code: 6\nmore")))
        assertNull(Updater.extractVersionCode(release("v2.0.1", "2.0.1", "no marker here")))
        assertNull(Updater.extractVersionCode(release("v2.0.1", "2.0.1", null)))
        assertNull(Updater.extractVersionCode(release("v2.0.1", "2.0.1", "novamusic-version-code: 0")))
    }

    @Test
    fun isUpdateAvailable_prefersVersionCodeOverVersionName() {
        // Higher version code wins even when the version name is not newer.
        val higherCode =
            release("v2.0.1", "2.0.0", "novamusic-version-code: 6")
        assertTrue(Updater.isUpdateAvailable(higherCode, "2.0.0", 5))

        // Lower version code is refused even when the version name looks newer —
        // this is what stops a mis-tagged release from downgrading devices.
        val lowerCode =
            release("v9.9.9", "9.9.9", "novamusic-version-code: 4")
        assertFalse(Updater.isUpdateAvailable(lowerCode, "2.0.1", 6))

        // Equal version code is not an update.
        val sameCode =
            release("v2.0.1", "2.0.1", "novamusic-version-code: 6")
        assertFalse(Updater.isUpdateAvailable(sameCode, "2.0.1", 6))
    }
}
