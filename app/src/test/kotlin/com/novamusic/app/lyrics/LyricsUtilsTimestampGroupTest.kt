package com.novamusic.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of [LyricsUtils.findCurrentLineIndex] when several lines share one timestamp.
 *
 * This is not a hypothetical case: translated lyrics are rendered as the original line
 * followed by its translation, and both carry the SAME timestamp. Before the group fix, the
 * binary search landed on the LAST line of the group, so the highlight sat on the translation
 * and the original line satisfied `index < currentLineIndex` — it was painted as "already
 * sung" while it was the line actually being played.
 *
 * Tap-to-seek is unaffected: it seeks to the tapped line's own `time`, which is identical
 * across a group, so tapping either line seeks to the same moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsUtilsTimestampGroupTest {
    private fun entries(vararg times: Long): List<LyricsEntry> =
        times.mapIndexed { index, time -> LyricsEntry(time, "line $index") }

    private fun indexAt(
        lines: List<LyricsEntry>,
        position: Long,
        leadMs: Long = 0L,
    ): Int = LyricsUtils.findCurrentLineIndex(lines, position, leadMs)

    @Test
    fun uniqueTimestampsAreUnaffected() {
        val lines = entries(1000, 2000, 3000)

        assertEquals(0, indexAt(lines, 1200))
        assertEquals(1, indexAt(lines, 2500))
        assertEquals(2, indexAt(lines, 9000))
    }

    @Test
    fun aTimestampGroupReportsItsFirstLineNotItsLast() {
        // An original line and its translation, sharing one timestamp.
        val lines = entries(1000, 1000)

        assertEquals(
            "the highlight must land on the original, not on the translation beneath it",
            0,
            indexAt(lines, 1500),
        )
    }

    @Test
    fun noLineOfTheActiveGroupIsMarkedAsAlreadySung() {
        // `isPast` is computed as `index < currentLineIndex`. Reporting the FIRST line of the
        // group means no member of the active group can fall on the "past" side.
        val lines = entries(1000, 1000, 5000, 5000)

        val current = indexAt(lines, 2000)
        assertEquals(0, current)
        for (index in 0..1) {
            assertEquals(
                "index $index belongs to the active group and must not be treated as past",
                false,
                index < current,
            )
        }
    }

    @Test
    fun advancingBeyondAGroupMovesToTheNextGroup() {
        val lines = entries(1000, 1000, 5000, 5000)

        assertEquals(2, indexAt(lines, 6000))
    }

    @Test
    fun threeLinesSharingATimestampReportTheFirst() {
        val lines = entries(1000, 1000, 1000)

        assertEquals(0, indexAt(lines, 4000))
    }

    @Test
    fun aPositionBeforeTheFirstLineReportsZero() {
        val lines = entries(1000, 1000)

        assertEquals(0, indexAt(lines, 0))
    }

    @Test
    fun anEmptyListReportsMinusOne() {
        assertEquals(-1, indexAt(emptyList(), 0))
    }

    @Test
    fun parsedInterleavedLyricsBehaveAsOneGroupPerLine() {
        // Exactly what the translated-lyrics renderer produces: each original line followed
        // by its translation at the same timestamp.
        val lines =
            LyricsUtils.parseLyrics(
                "[00:01.00] Hello\n[00:01.00] Bonjour\n[00:05.00] World\n[00:05.00] Monde",
            )

        assertEquals(4, lines.size)
        assertEquals(listOf(1000L, 1000L, 5000L, 5000L), lines.map { it.time })

        assertEquals(0, indexAt(lines, 2000))
        assertEquals(2, indexAt(lines, 6000))
    }
}
