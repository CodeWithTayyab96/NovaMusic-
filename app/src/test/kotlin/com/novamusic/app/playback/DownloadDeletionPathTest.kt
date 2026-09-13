package com.novamusic.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the download-deletion path rule.
 *
 * The field bug: localPath holds the raw MediaStore DATA column, a plain filesystem
 * path with no URI scheme, and deletion only handled "content"/"file" schemes — so the
 * file was never removed and the song merely vanished from the app's list.
 */
class DownloadDeletionPathTest {
    // ─── paths that must be deletable ───────────────────────────────────────

    @Test
    fun schemeLessPathInsideDownloadFolder_isDeletable() {
        // The exact shape of a real localPath — no scheme at all.
        assertTrue(
            isDeletableDownloadPath("/storage/emulated/0/Music/NovaMusic/song.m4a"),
        )
    }

    @Test
    fun fileUriInsideDownloadFolder_isDeletable() {
        assertTrue(
            isDeletableDownloadPath("file:///storage/emulated/0/Music/NovaMusic/song.m4a"),
        )
    }

    @Test
    fun contentUri_isDeletable() {
        assertTrue(
            isDeletableDownloadPath(
                "content://media/external_primary/audio/media/1234",
            ),
        )
    }

    @Test
    fun folderMatchIsCaseInsensitive() {
        assertTrue(isDeletableDownloadPath("/storage/emulated/0/Music/novamusic/song.m4a"))
        assertTrue(isDeletableDownloadPath("/storage/emulated/0/Music/NOVAMUSIC/song.m4a"))
    }

    // ─── paths that must never be deletable ─────────────────────────────────

    @Test
    fun unrelatedMusicFile_isNotDeletable() {
        assertFalse(isDeletableDownloadPath("/storage/emulated/0/Music/SomeOtherSong.mp3"))
        assertFalse(isDeletableDownloadPath("/storage/emulated/0/Download/holiday.mp4"))
    }

    @Test
    fun playbackCache_isNotDeletable() {
        // Streaming cache must never be reachable through the download-deletion path.
        assertFalse(
            isDeletableDownloadPath("/data/user/0/com.novamusic.app/files/exoplayer/abc.0"),
        )
    }

    @Test
    fun blankOrEmpty_isNotDeletable() {
        assertFalse(isDeletableDownloadPath(""))
        assertFalse(isDeletableDownloadPath("   "))
    }

    @Test
    fun fileUriOutsideDownloadFolder_isNotDeletable() {
        assertFalse(isDeletableDownloadPath("file:///storage/emulated/0/Music/other.mp3"))
    }

    @Test
    fun folderNameIsConfigurable() {
        assertTrue(
            isDeletableDownloadPath(
                "/storage/emulated/0/Music/CustomFolder/song.m4a",
                folderName = "CustomFolder",
            ),
        )
        assertFalse(
            isDeletableDownloadPath(
                "/storage/emulated/0/Music/NovaMusic/song.m4a",
                folderName = "CustomFolder",
            ),
        )
    }
}
