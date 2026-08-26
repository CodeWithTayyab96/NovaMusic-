package com.novamusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests verifying that [storeMimeForContainer] maps audio/webm streams to
 * audio/x-matroska for MediaStore insertion, avoiding the OEM Unsupported MIME type error.
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
}
