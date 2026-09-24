/*
 * NovaMusic — GPL-3.0.
 *
 * Behavioural tests for the ported parametric EQ processor. These run the real Media3
 * AudioProcessor contract (configure → queueInput → getOutput), not a mock, because the
 * things most likely to break are the buffer and format handling.
 *
 * The gain assertions use a generated sine at the band's centre frequency, where an RBJ
 * peaking filter's gain is exactly the requested value by construction.
 */

package com.novamusic.app.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.novamusic.app.eq.audio.ParametricEqualizerAudioProcessor
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class ParametricEqualizerAudioProcessorTest {

    // ------------------------------------------------------------------ pass-through

    @Test
    fun `a flat curve passes int16 through unchanged`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 0.0))))

        assertFalse("a flat curve must not engage the processor", processor.isEnabled)

        val input = sineInt16(1000.0, 1, FRAMES, 0.5)
        val expected = input.copyBytes()
        val output = process(processor, input)

        assertArrayEquals(expected, output.copyBytes())
    }

    @Test
    fun `a flat curve passes float through unchanged`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(2, C.ENCODING_PCM_FLOAT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 0.0))))

        val input = sineFloat(1000.0, 2, FRAMES, 0.5)
        val expected = input.copyBytes()
        val output = process(processor, input)

        assertArrayEquals(expected, output.copyBytes())
    }

    @Test
    fun `a disabled processor passes through unchanged`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0))))
        assertTrue(processor.isEnabled)

        processor.disable()
        assertFalse(processor.isEnabled)

        val input = sineInt16(1000.0, 1, FRAMES, 0.5)
        val expected = input.copyBytes()
        assertArrayEquals(expected, process(processor, input).copyBytes())
    }

    // ------------------------------------------------------------------ gain accuracy

    @Test
    fun `a peaking band boosts its centre frequency by the requested amount, mono`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0, q = 1.0))))

        val inputRms = rmsInt16(sineInt16(1000.0, 1, FRAMES, 0.25), 1)
        val outputRms = rmsInt16(process(processor, sineInt16(1000.0, 1, FRAMES, 0.25)), 1)

        assertEquals(6.0, gainDb(inputRms, outputRms), 0.5)
    }

    @Test
    fun `a peaking band cuts its centre frequency by the requested amount, stereo`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(2, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = -6.0, q = 1.0))))

        val inputRms = rmsInt16(sineInt16(1000.0, 2, FRAMES, 0.25), 2)
        val outputRms = rmsInt16(process(processor, sineInt16(1000.0, 2, FRAMES, 0.25)), 2)

        assertEquals(-6.0, gainDb(inputRms, outputRms), 0.5)
    }

    @Test
    fun `the gain is applied in float PCM too`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_FLOAT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0, q = 1.0))))

        val inputRms = rmsFloat(sineFloat(1000.0, 1, FRAMES, 0.25), 1)
        val outputRms = rmsFloat(process(processor, sineFloat(1000.0, 1, FRAMES, 0.25)), 1)

        assertEquals(6.0, gainDb(inputRms, outputRms), 0.5)
    }

    @Test
    fun `the preamp attenuates the whole signal`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = -6.0, bands = emptyList()))

        assertTrue("a non-unity preamp must engage the processor", processor.isEnabled)

        val inputRms = rmsInt16(sineInt16(1000.0, 1, FRAMES, 0.25), 1)
        val outputRms = rmsInt16(process(processor, sineInt16(1000.0, 1, FRAMES, 0.25)), 1)

        assertEquals(-6.0, gainDb(inputRms, outputRms), 0.5)
    }

    // ------------------------------------------------------------------ channel handling

    @Test
    fun `stereo channels are filtered independently`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(2, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0, q = 1.0))))

        // Left carries a tone, right is silent. The right channel must stay silent: if the
        // two channels shared filter state, the left tone would leak into the right output.
        val buffer = ByteBuffer.allocateDirect(FRAMES * 2 * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until FRAMES) {
            buffer.putShort((0.25 * sin(2 * PI * 1000.0 * frame / SAMPLE_RATE) * 32767.0).toInt().toShort())
            buffer.putShort(0)
        }
        buffer.flip()

        val output = process(processor, buffer)
        var maxRight = 0
        val dup = output.duplicate().order(ByteOrder.nativeOrder())
        while (dup.remaining() >= 4) {
            dup.getShort()
            maxRight = maxOf(maxRight, kotlin.math.abs(dup.getShort().toInt()))
        }
        assertEquals("the silent channel must stay silent", 0, maxRight)
    }

    @Test
    fun `mono output has the same length as the input`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0))))

        val input = sineInt16(1000.0, 1, FRAMES, 0.25)
        val expectedBytes = input.remaining()
        assertEquals(expectedBytes, process(processor, input).remaining())
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun `output never contains NaN or infinity`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(2, C.ENCODING_PCM_FLOAT))
        // A deliberately brutal curve: maximum preamp, maximum boosts, extreme Q.
        processor.applyProfile(
            ParametricEq(
                preamp = 20.0,
                bands =
                    listOf(
                        band(20.0, gain = 30.0, q = 0.1),
                        band(200.0, gain = 30.0, q = 0.1),
                        band(20000.0, gain = 30.0, q = 20.0),
                    ),
            ),
        )

        val output = process(processor, sineFloat(200.0, 2, FRAMES, 0.9))
        val dup = output.duplicate().order(ByteOrder.nativeOrder())
        var checked = 0
        while (dup.remaining() >= 4) {
            val sample = dup.getFloat()
            assertTrue("non-finite sample: $sample", sample.isFinite())
            assertTrue("sample out of range: $sample", sample in -1.0f..1.0f)
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `a large boost is clamped instead of wrapping around`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 24.0, q = 1.0))))

        // A 24 dB boost is ~15.9x. On a 0.9-amplitude sine that is far beyond full scale, so
        // the steady state must sit pinned at both rails. Without the clamp the values would
        // wrap and the sign would flip, so the rails would never be reached.
        val samples = process(processor, sineInt16(1000.0, 1, FRAMES, 0.9)).toShortArray()
        val steadyState = samples.drop(SETTLE_FRAMES)

        assertEquals("positive rail must be reached", 32767.toShort(), steadyState.maxOrNull())
        assertEquals("negative rail must be reached", (-32768).toShort(), steadyState.minOrNull())

        val atRail = steadyState.count { it == 32767.toShort() || it == (-32768).toShort() }
        assertTrue(
            "a heavily boosted signal must sit at the rails, not wrap (found $atRail of ${steadyState.size})",
            atRail > steadyState.size / 2,
        )
    }

    // ------------------------------------------------------------------ reconfiguration

    @Test
    fun `an unsupported encoding bypasses instead of throwing`() {
        val processor = ParametricEqualizerAudioProcessor()
        val out = processor.configure(format(1, C.ENCODING_PCM_24BIT))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        assertFalse(processor.isActive())
    }

    @Test
    fun `more than two channels bypasses instead of throwing`() {
        val processor = ParametricEqualizerAudioProcessor()
        val out = processor.configure(format(6, C.ENCODING_PCM_16BIT))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        assertFalse(processor.isActive())
    }

    @Test
    fun `a sample rate change rebuilds the curve and keeps the same gain`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0, q = 1.0))))
        assertTrue(processor.isEnabled)

        // The sink reconfigures mid-stream at a different rate.
        val newRate = 48_000
        processor.configure(AudioProcessor.AudioFormat(newRate, 1, C.ENCODING_PCM_16BIT))
        assertTrue("the curve must survive a sample rate change", processor.isEnabled)

        val inputRms = rmsInt16(sineInt16(1000.0, 1, FRAMES, 0.25, newRate), 1)
        val outputRms = rmsInt16(process(processor, sineInt16(1000.0, 1, FRAMES, 0.25, newRate)), 1)

        assertEquals("coefficients must be recomputed for the new rate", 6.0, gainDb(inputRms, outputRms), 0.5)
    }

    @Test
    fun `a profile applied before configure is picked up afterwards`() {
        val processor = ParametricEqualizerAudioProcessor()
        processor.applyProfile(ParametricEq(preamp = 0.0, bands = listOf(band(1000.0, gain = 6.0, q = 1.0))))
        assertFalse("nothing can be applied before the format is known", processor.isEnabled)

        processor.configure(format(1, C.ENCODING_PCM_16BIT))
        assertTrue(processor.isEnabled)
    }

    // ------------------------------------------------------------------ helpers

    private fun format(channels: Int, encoding: Int) =
        AudioProcessor.AudioFormat(SAMPLE_RATE, channels, encoding)

    private fun band(frequency: Double, gain: Double, q: Double = 1.41) =
        ParametricEqBand(frequency = frequency, gain = gain, q = q)

    private fun process(processor: ParametricEqualizerAudioProcessor, input: ByteBuffer): ByteBuffer {
        processor.queueInput(input)
        return processor.getOutput()
    }

    /** A steady sine at [frequency], interleaved across [channels]. */
    private fun sineInt16(
        frequency: Double,
        channels: Int,
        frames: Int,
        amplitude: Double,
        sampleRate: Int = SAMPLE_RATE,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val sample = (amplitude * sin(2 * PI * frequency * frame / sampleRate) * 32767.0).toInt().toShort()
            repeat(channels) { buffer.putShort(sample) }
        }
        buffer.flip()
        return buffer
    }

    private fun sineFloat(
        frequency: Double,
        channels: Int,
        frames: Int,
        amplitude: Double,
        sampleRate: Int = SAMPLE_RATE,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val sample = (amplitude * sin(2 * PI * frequency * frame / sampleRate)).toFloat()
            repeat(channels) { buffer.putFloat(sample) }
        }
        buffer.flip()
        return buffer
    }

    /** RMS of the steady-state part of an int16 buffer, skipping the filter's settling. */
    private fun rmsInt16(buffer: ByteBuffer, channels: Int): Double {
        val dup = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = dup.remaining() / 2 / channels
        var sum = 0.0
        var count = 0
        for (frame in 0 until frames) {
            val left = dup.getShort() / 32768.0
            repeat(channels - 1) { dup.getShort() }
            if (frame >= SETTLE_FRAMES) {
                sum += left * left
                count++
            }
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun rmsFloat(buffer: ByteBuffer, channels: Int): Double {
        val dup = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = dup.remaining() / 4 / channels
        var sum = 0.0
        var count = 0
        for (frame in 0 until frames) {
            val left = dup.getFloat().toDouble()
            repeat(channels - 1) { dup.getFloat() }
            if (frame >= SETTLE_FRAMES) {
                sum += left * left
                count++
            }
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun gainDb(inputRms: Double, outputRms: Double): Double =
        20.0 * log10(outputRms / inputRms)

    private fun ByteBuffer.copyBytes(): ByteArray {
        val dup = duplicate().order(ByteOrder.nativeOrder())
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun ByteBuffer.toShortArray(): List<Short> {
        val dup = duplicate().order(ByteOrder.nativeOrder())
        val out = ArrayList<Short>(dup.remaining() / 2)
        while (dup.remaining() >= 2) out.add(dup.getShort())
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAMES = 16_384

        /** Frames ignored at the start so the biquad's transient does not skew RMS. */
        const val SETTLE_FRAMES = 2_048
    }
}
