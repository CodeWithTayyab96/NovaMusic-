package com.novamusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests verifying [storeMimeForContainer] and [detectContainer] in LocalFileDownloader.
 */
class LocalFileDownloaderMimeTest {

    @Test
    fun audioWebmMime_mapsToMatroskaForMediaStore() {
        assertEquals("audio/x-matroska", storeMimeForContainer("audio/webm", null, "webm"))
        assertEquals("audio/x-matroska", storeMimeForContainer("audio/webm; codecs=\"opus\"", null, "webm"))
    }

    @Test
    fun videoWebm_withExpectedAudioWebm_mapsToMatroskaForMediaStore() {
        assertEquals("audio/x-matroska", storeMimeForContainer("video/webm", "audio/webm", "webm"))
    }

    @Test
    fun octetStream_withWebmExtension_mapsToMatroskaForMediaStore() {
        assertEquals("audio/x-matroska", storeMimeForContainer("application/octet-stream", null, "webm"))
    }

    @Test
    fun standardAudioMimes_retainTheirOriginalFormat() {
        assertEquals("audio/mp4", storeMimeForContainer("audio/mp4", null, "m4a"))
        assertEquals("audio/mpeg", storeMimeForContainer("audio/mpeg", null, "mp3"))
        assertEquals("audio/flac", storeMimeForContainer("audio/flac", null, "flac"))
        assertEquals("audio/ogg", storeMimeForContainer("audio/ogg", null, "ogg"))
    }

    @Test
    fun detectContainer_identifiesMp4FtypAndStyp() {
        val mp4Bytes = byteArrayOf(0, 0, 0, 32, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte())
        val detected = detectContainer(ByteArrayInputStream(mp4Bytes))
        assertEquals("MP4/M4A", detected)
    }

    @Test
    fun detectContainer_identifiesWebmEbmlHeader() {
        val webmBytes = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x00, 0x00)
        val detected = detectContainer(ByteArrayInputStream(webmBytes))
        assertEquals("WebM/EBML", detected)
    }

    @Test
    fun detectContainer_identifiesAdtsAac() {
        val aacBytes = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte(), 0x00)
        val detected = detectContainer(ByteArrayInputStream(aacBytes))
        assertEquals("AAC (ADTS)", detected)
    }

    @Test
    fun extensionForMime_returnsCorrectExtensions() {
        assertEquals("webm", extensionForMime("audio/webm"))
        assertEquals("m4a", extensionForMime("audio/mp4"))
        assertEquals("m4a", extensionForMime("audio/m4a"))
        assertEquals("mp3", extensionForMime("audio/mpeg"))
        assertEquals("flac", extensionForMime("audio/flac"))
        assertEquals("ogg", extensionForMime("audio/ogg"))
    }
}
