package com.novamusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the container/codec split in [extensionForMime]: the extension must
 * follow the *container* (webm -> .webm, mp4 -> .m4a), never the codec inside.
 * YouTube serves audio-only webm streams (itag 249/250/251) in a WebM/EBML
 * container with Opus or Vorbis inside — those are NOT bare Ogg-Opus files and
 * must be saved as ".webm" or strict external players (VLC) refuse them.
 */
class ExtensionForMimeTest {

    @Test
    fun webmContainer_usesWebmExtension_regardlessOfCodec() {
        assertEquals("webm", extensionForMime("audio/webm"))
        assertEquals("webm", extensionForMime("audio/webm; codecs=\"opus\""))
        assertEquals("webm", extensionForMime("audio/webm; codecs=\"vorbis\""))
        // CDN sometimes mislabels audio-only streams as video/*
        assertEquals("webm", extensionForMime("video/webm"))
    }

    @Test
    fun mp4Container_usesM4aExtension() {
        assertEquals("m4a", extensionForMime("audio/mp4"))
        assertEquals("m4a", extensionForMime("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("m4a", extensionForMime("audio/x-m4a"))
    }

    @Test
    fun realOggOpusContainer_usesOggExtension() {
        // A genuine Ogg container (OggS magic) — not what YouTube serves for
        // webm/opus streams, but the extension must match the container either way.
        assertEquals("ogg", extensionForMime("audio/ogg; codecs=\"opus\""))
        assertEquals("ogg", extensionForMime("audio/ogg"))
    }

    @Test
    fun otherAudioContainers_mapToTheirOwnExtension() {
        assertEquals("flac", extensionForMime("audio/flac"))
        assertEquals("wav", extensionForMime("audio/wav"))
        assertEquals("aac", extensionForMime("audio/aac"))
        assertEquals("mp3", extensionForMime("audio/mpeg"))
    }

    @Test
    fun unknownMime_fallsBackToM4a() {
        assertEquals("m4a", extensionForMime(""))
        assertEquals("m4a", extensionForMime("application/octet-stream"))
    }

    @Test
    fun mimeIsCaseInsensitive() {
        assertEquals("webm", extensionForMime("AUDIO/WEBM"))
        assertEquals("m4a", extensionForMime("Audio/MP4; Codecs=\"mp4a.40.2\""))
    }
}
