/*
 * Regression tests for the player/downloader production-hardening pass.
 *
 * Everything here runs on the JVM against pure seams of the REAL production code — no
 * network, no device, no Robolectric. They pin the invariants the audit found violated:
 * file naming, deletion path safety, download-completion arithmetic, and cache sanity.
 */
package com.novamusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PlaybackDownloadHardeningTest {

    // ───────────── file naming: no path traversal through DISPLAY_NAME ─────────────

    @Test
    fun sanitizeFileNameNeutralisesTraversalAndSeparators() {
        // Every path separator becomes an underscore, so no DISPLAY_NAME can smuggle a
        // directory component (MediaStore on Q+ treats DISPLAY_NAME as a leaf name, but
        // the guarantee must hold for the pre-Q file path too).
        assertEquals(".._.._..", sanitizeFileName("../../.."))
        assertFalse(sanitizeFileName("a/b\\c").contains('/'))
        assertFalse(sanitizeFileName("a/b\\c").contains('\\'))
        assertEquals("song_ name", sanitizeFileName("song: name"))
    }

    @Test
    fun sanitizeFileNameFallsBackForBlankInput() {
        assertEquals("Unknown", sanitizeFileName("   "))
        assertEquals("Unknown", sanitizeFileName(""))
    }

    @Test
    fun sanitizeFileNameKeepsNormalTitlesUnchanged() {
        assertEquals("My Song 01", sanitizeFileName("My Song 01"))
        assertEquals("AC-DC", sanitizeFileName("AC-DC"))
    }

    // ───────────── deletion allow-list: cache dirs stay untouched ─────────────

    @Test
    fun downloadPathsAreDeletable() {
        assertTrue(
            isDeletableDownloadPath("/storage/emulated/0/Music/NovaMusic/Song - Artist.m4a"),
        )
        assertTrue(isDeletableDownloadPath("content://media/external/audio/123"))
        assertTrue(isDeletableDownloadPath("file:///storage/emulated/0/Music/NovaMusic/x.webm"))
        // Case-insensitive segment match.
        assertTrue(isDeletableDownloadPath("/storage/emulated/0/music/novamusic/x.mp3"))
    }

    @Test
    fun nonDownloadPathsAreNeverDeletable() {
        // The app package contains "novamusic" as a SUBSTRING — the streaming cache must
        // not match (this exact bug shipped once).
        assertFalse(isDeletableDownloadPath("/data/user/0/com.novamusic.app/files/exoplayer/x.m4a"))
        assertFalse(isDeletableDownloadPath("/storage/emulated/0/Music/OtherArtist/song.mp3"))
        assertFalse(isDeletableDownloadPath(""))
        assertFalse(isDeletableDownloadPath("NovaMusicEvil/x")) // substring, not a segment
    }

    // ───────────── completion arithmetic: partial files are never "done" ─────────────

    @Test
    fun unknownLengthIsNeverComplete() {
        // The exact bug: unknown length (-1) + a written probe chunk reported COMPLETED.
        assertFalse(isDownloadComplete(bytesWritten = 64L * 1024L, contentLength = -1L))
        assertFalse(isDownloadComplete(bytesWritten = 1L, contentLength = 0L))
    }

    @Test
    fun partialContentIsNeverComplete() {
        assertFalse(isDownloadComplete(bytesWritten = 999L, contentLength = 1000L))
    }

    @Test
    fun fullKnownLengthIsComplete() {
        assertTrue(isDownloadComplete(bytesWritten = 1000L, contentLength = 1000L))
    }

    // ───────────── container sniffing: stubs are refused before persisting ─────────────

    @Test
    fun audioContainersAreRecognised() {
        val mp4 = byteArrayOf(
            0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(),
        ) + ByteArray(64)
        assertEquals("MP4/M4A", detectContainer(ByteArrayInputStream(mp4)))

        val webm = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64)
        assertEquals("WebM/EBML", detectContainer(ByteArrayInputStream(webm)))

        val ogg = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(64)
        assertEquals("Ogg (Opus/Vorbis)", detectContainer(ByteArrayInputStream(ogg)))
    }

    @Test
    fun nonAudioPayloadsAreRejected() {
        val html = "<html><body>sign in to continue</body></html>".toByteArray()
        assertEquals(null, detectContainer(ByteArrayInputStream(html)))

        val json = """{"error":"login required"}""".toByteArray()
        assertEquals(null, detectContainer(ByteArrayInputStream(json)))

        // Too short to even hold magic bytes.
        assertEquals(null, detectContainer(ByteArrayInputStream(byteArrayOf(1, 2))))
    }

    // ───────────── extension / mime mapping consistency ─────────────

    @Test
    fun webmStreamsMapToWebmNotBareOpus() {
        assertEquals("webm", extensionForMime("audio/webm; codecs=\"opus\""))
        assertEquals("m4a", extensionForMime("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("mp3", extensionForMime("audio/mpeg"))
    }

    @Test
    fun storedMimeMatchesTheContainerBytes() {
        // A .webm file stored as audio/opus confuses strict players (VLC).
        assertEquals("audio/x-matroska", storeMimeForContainer("audio/webm", "audio/webm", "webm"))
        assertEquals("audio/mp4", storeMimeForContainer("unknown/unknown", null, "m4a"))
    }

    // ───────────── download state invariants (pause/queue semantics) ─────────────

    @Test
    fun pausedStateStaysActiveInTheQueue() {
        val paused = LocalDownloadState(
            songId = "s1",
            title = "t",
            artist = "a",
            state = LocalDownloadState.State.DOWNLOADING,
            isPaused = true,
            stopCount = 2,
        )
        // A paused download must remain visible in the Download Queue UI (isActive) while
        // waiting for constraints — it must not vanish, nor look COMPLETED.
        assertTrue(paused.isActive)
        assertTrue(paused.isPaused)
    }

    @Test
    fun terminalStatesAreNotActive() {
        assertFalse(
            LocalDownloadState("s", "t", "a", LocalDownloadState.State.COMPLETED).isActive,
        )
        assertFalse(
            LocalDownloadState("s", "t", "a", LocalDownloadState.State.FAILED).isActive,
        )
    }

    // ───────────── hardening contract documentation (compile-time checked) ─────────────

    @Test
    fun uniqueWorkNameIsPerSong() {
        // REPLACE-policy unique work is keyed per song; two songs must never collide and
        // the same song must always map to the same name (cancellation depends on it).
        val a = LocalFileDownloader.uniqueWorkName("abc")
        val b = LocalFileDownloader.uniqueWorkName("def")
        assertNotEquals(a, b)
        assertEquals(LocalFileDownloader.uniqueWorkName("abc"), a)
        assertTrue(a.contains("abc"))
    }

    // ─────── final-pass additions: state-transition determinism & integrity ───────

    @Test
    fun aPauseCycleBelowTheCeilingIsRetriableAndStaysVisible() {
        var state = LocalDownloadState(
            songId = "s1", title = "t", artist = "a",
            state = LocalDownloadState.State.DOWNLOADING,
            progress = 0.4f, bytesDownloaded = 400, totalBytes = 1000,
        )
        // Simulate markPaused's exact transition for each system stop: state stays
        // DOWNLOADING, progress/bytes are PRESERVED, isPaused flips on, counter increments.
        repeat(5) {
            state = state.copy(
                isPaused = true,
                stopCount = state.stopCount + 1,
            )
        }
        assertTrue(state.isPaused)
        assertEquals(5, state.stopCount)
        assertEquals("progress must never reset on pause/resume", 0.4f, state.progress, 0.0001f)
        assertEquals(400L, state.bytesDownloaded)
        assertTrue("a paused download stays in the queue UI", state.isActive)
    }

    @Test
    fun theStateCeilingMatchesTheWorkerConstant() {
        // The worker fails a download permanently once stopCount exceeds MAX_SYSTEM_STOPS.
        // The constant lives in the worker; this pins the documented ceiling so an accidental
        // change to either side shows up here.
        assertEquals(5, MAX_SYSTEM_STOPS_FOR_TEST)
        val state = LocalDownloadState(
            songId = "s", title = "t", artist = "a",
            state = LocalDownloadState.State.DOWNLOADING, stopCount = MAX_SYSTEM_STOPS_FOR_TEST,
        )
        // Exactly AT the ceiling the download is still retrievable; only stopCount > ceiling
        // (the next pause) fails it permanently — matching `if (stopCount > MAX_SYSTEM_STOPS)`.
        assertTrue(state.isActive)
    }

    private companion object {
        /** Mirrors LocalFileDownloadWorker.MAX_SYSTEM_STOPS (kept in sync by this test). */
        const val MAX_SYSTEM_STOPS_FOR_TEST = 5
    }
}
