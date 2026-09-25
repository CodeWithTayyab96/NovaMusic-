/*
 * NovaMusic — GPL-3.0.
 *
 * Covers the parser half of the ported parametric EQ: what it accepts, what it rejects,
 * and that a profile survives a write/read round trip.
 */

package com.novamusic.app.eq

import com.novamusic.app.eq.data.FilterType
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqParserTest {

    private val validProfile =
        """
        Preamp: -6.5 dB
        Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON LSC Fc 60 Hz Gain 4.0 dB Q 0.70
        Filter 3: ON HSC Fc 8000 Hz Gain -3.0 dB Q 1.00
        """.trimIndent()

    @Test
    fun `parses a valid profile`() {
        val result = ParametricEqParser.parse(validProfile)

        assertTrue("expected success, got ${result.exceptionOrNull()?.message}", result.isSuccess)
        val eq = result.getOrThrow()
        assertEquals(-6.5, eq.preamp, 1e-6)
        assertEquals(3, eq.bands.size)
        assertEquals(FilterType.PK, eq.bands[0].filterType)
        assertEquals(105.0, eq.bands[0].frequency, 1e-6)
        assertEquals(5.5, eq.bands[0].gain, 1e-6)
        assertEquals(0.70, eq.bands[0].q, 1e-6)
        assertEquals(FilterType.LSC, eq.bands[1].filterType)
        assertEquals(FilterType.HSC, eq.bands[2].filterType)
        assertEquals(-3.0, eq.bands[2].gain, 1e-6)
    }

    @Test
    fun `keeps unknown key-value lines as metadata`() {
        val eq = ParametricEqParser.parseText("Preamp: 0 dB\nAuthor: someone\n$validProfile")
        assertEquals("someone", eq.metadata["Author"])
    }

    @Test
    fun `rejects an empty file`() {
        val result = ParametricEqParser.parse("   \n  \n")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("empty", ignoreCase = true))
    }

    @Test
    fun `rejects a file with no filter bands`() {
        val result = ParametricEqParser.parse("Preamp: -3 dB\nAuthor: nobody\n")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No filter bands", ignoreCase = true))
    }

    @Test
    fun `rejects a gain outside the allowed range`() {
        val result = ParametricEqParser.parse("Filter 1: ON PK Fc 100 Hz Gain 99 dB Q 1.0")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("gain", ignoreCase = true))
    }

    @Test
    fun `rejects a non-positive Q`() {
        val result = ParametricEqParser.parse("Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 0")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Q", ignoreCase = true))
    }

    @Test
    fun `rejects a profile larger than the cap`() {
        val huge = "Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 1.0\n".repeat(20_000)
        assertTrue(huge.length > ParametricEqParser.MAX_PROFILE_BYTES)
        val result = ParametricEqParser.parse(huge)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("KiB"))
    }

    @Test
    fun `skips bands that are not switched on`() {
        val eq = ParametricEqParser.parseText("Filter 1: OFF PK Fc 100 Hz Gain 3 dB Q 1.0")
        assertEquals(0, eq.bands.size)
    }

    @Test
    fun `never parses more than the maximum number of bands`() {
        val many = (1..40).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain 1 dB Q 1.0" }
        val eq = ParametricEqParser.parseText(many)
        assertEquals(ParametricEq.MAX_BANDS, eq.bands.size)
    }

    @Test
    fun `round trips through the file format`() {
        val original = ParametricEqParser.parse(validProfile).getOrThrow()
        val reparsed = ParametricEqParser.parseText(ParametricEqParser.toFileFormat(original))

        assertEquals(original.bands.size, reparsed.bands.size)
        original.bands.forEachIndexed { index, band ->
            assertEquals(band.filterType, reparsed.bands[index].filterType)
            assertEquals(band.frequency.toInt(), reparsed.bands[index].frequency.toInt())
            assertEquals(band.gain, reparsed.bands[index].gain, 1e-6)
            assertEquals(band.q, reparsed.bands[index].q, 0.01)
        }
    }

    @Test
    fun `a curve with only zero gains is flat`() {
        val eq = ParametricEqParser.parse("Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0").getOrThrow()
        assertTrue(eq.isFlat)
    }

    @Test
    fun `a curve with a real gain is not flat`() {
        val eq = ParametricEqParser.parse("Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 1.0").getOrThrow()
        assertTrue(!eq.isFlat)
    }
}
