package com.novamusic.app.innertube.utils

import com.novamusic.app.innertube.models.Artist
import com.novamusic.app.innertube.models.SongItem
import com.novamusic.app.innertube.pages.PlaylistContinuationPage
import com.novamusic.app.innertube.pages.PlaylistPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {

    // ---------- parseTime ----------

    @Test
    fun parseTime_minutesAndSeconds() {
        assertEquals(225, "3:45".parseTime())
    }

    @Test
    fun parseTime_hoursMinutesSeconds() {
        assertEquals(3723, "1:02:03".parseTime())
    }

    @Test
    fun parseTime_dotSeparator() {
        assertEquals(270, "4.30".parseTime())
    }

    @Test
    fun parseTime_zero() {
        assertEquals(0, "0:00".parseTime())
    }

    @Test
    fun parseTime_trailingWhitespace() {
        assertEquals(225, "3:45 ".parseTime())
    }

    @Test
    fun parseTime_invalidInput_returnsNull() {
        assertNull("abc".parseTime())
        assertNull("".parseTime())
        assertNull(":30".parseTime())
        assertNull("1:60".parseTime())
        assertNull("1:2:3:4".parseTime())
        assertNull("12:34:567".parseTime())
    }

    // ---------- sha1 ----------

    @Test
    fun sha1_knownVector() {
        // RFC 3174 test vector: SHA-1("abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc"))
    }

    @Test
    fun sha1_emptyString() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(""))
    }

    // ---------- completePlaylistPage ----------

    private fun song(id: String) = SongItem(
        id = id,
        title = "Song $id",
        artists = listOf(Artist(name = "Artist", id = "channel-1")),
        thumbnail = "https://example.com/$id.jpg",
    )

    private fun page(songs: List<SongItem>, songsContinuation: String?, continuation: String? = null) =
        PlaylistPage(
            playlist = com.novamusic.app.innertube.models.PlaylistItem(
                id = "PL1",
                title = "Test Playlist",
                author = null,
                songCountText = null,
                thumbnail = null,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            ),
            songs = songs,
            songsContinuation = songsContinuation,
            continuation = continuation,
        )

    @Test
    fun completePlaylistPage_collectsUntilContinuationEnds() {
        val fetcher: suspend (String) -> PlaylistContinuationPage? = { c ->
            when (c) {
                "c1" -> PlaylistContinuationPage(listOf(song("b")), "c2")
                "c2" -> PlaylistContinuationPage(listOf(song("c")), null)
                else -> null
            }
        }

        val result = runBlocking {
            completePlaylistPage(
                page = page(listOf(song("a")), songsContinuation = "c1"),
                fetchContinuationPage = fetcher,
            )
        }

        assertEquals(listOf("a", "b", "c"), result.songs.map { it.id })
        assertNull(result.songsContinuation)
        assertNull(result.continuation)
    }

    @Test
    fun completePlaylistPage_terminatesOnContinuationCycle() {
        // "c1" -> "c2" -> "c1" -> ... must terminate, not loop forever.
        val fetcher: suspend (String) -> PlaylistContinuationPage? = { c ->
            when (c) {
                "c1" -> PlaylistContinuationPage(listOf(song("b")), "c2")
                "c2" -> PlaylistContinuationPage(listOf(song("c")), "c1")
                else -> null
            }
        }

        val result = runBlocking {
            completePlaylistPage(
                page = page(listOf(song("a")), songsContinuation = "c1"),
                fetchContinuationPage = fetcher,
            )
        }

        assertEquals(listOf("a", "b", "c"), result.songs.map { it.id })
    }

    @Test
    fun completePlaylistPage_stopsAfterTwoConsecutiveEmptyResponses() {
        var requests = 0
        val fetcher: suspend (String) -> PlaylistContinuationPage? = {
            requests++
            PlaylistContinuationPage(emptyList(), "next")
        }

        val result = runBlocking {
            completePlaylistPage(
                page = page(listOf(song("a")), songsContinuation = "start"),
                fetchContinuationPage = fetcher,
            )
        }

        // Two empty pages in a row stop the loop.
        assertEquals(2, requests)
        assertEquals(listOf("a"), result.songs.map { it.id })
    }

    @Test
    fun completePlaylistPage_respectsMaxRequests() {
        var requests = 0
        // Each fetch returns a UNIQUE continuation so cycle detection never
        // triggers; only the max-requests cap can stop the loop.
        val fetcher: suspend (String) -> PlaylistContinuationPage? = {
            requests++
            PlaylistContinuationPage(listOf(song("x$requests")), "c$requests")
        }

        val result = runBlocking {
            completePlaylistPage(
                page = page(listOf(song("a")), songsContinuation = "start"),
                fetchContinuationPage = fetcher,
            )
        }

        assertEquals(50, requests)
        // 1 initial + 50 fetched
        assertEquals(51, result.songs.size)
        assertTrue(result.songs.last().id == "x50")
    }

    @Test
    fun completePlaylistPage_nullFetchResultStops() {
        val fetcher: suspend (String) -> PlaylistContinuationPage? = { null }

        val result = runBlocking {
            completePlaylistPage(
                page = page(listOf(song("a")), songsContinuation = "start"),
                fetchContinuationPage = fetcher,
            )
        }

        assertEquals(listOf("a"), result.songs.map { it.id })
    }
}
