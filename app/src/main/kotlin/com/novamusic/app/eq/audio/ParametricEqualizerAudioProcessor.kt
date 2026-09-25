/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/audio/CustomEqualizerAudioProcessor.kt
 * Adapted for NovaMusic:
 *   - extends BaseAudioProcessor (Media3 1.10.0) instead of implementing AudioProcessor, so
 *     the deprecated flush() override and the hand-rolled buffer juggling are gone
 *   - float PCM is supported alongside 16-bit PCM; anything else bypasses instead of
 *     throwing UnhandledAudioFormatException (Echo threw, which killed playback)
 *   - a disabled or flat curve is a pure pass-through with no DSP and no allocation
 *   - coefficients are published through an immutable, @Volatile snapshot so a UI-thread
 *     update can never be observed half-applied by the audio thread
 *   - preamp plus an output clamp, so a boosted curve cannot wrap around
 *
 * Buffer contract verified against the Media3 1.10.0 bytecode:
 *   BaseAudioProcessor.replaceOutputBuffer(n) reuses its cached direct buffer when the
 *   capacity is already sufficient, and BaseAudioProcessor.getOutput() returns the buffer
 *   WITHOUT flipping it — so queueInput() is responsible for the flip().
 */

package com.novamusic.app.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.novamusic.app.eq.data.ParametricEq
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * Applies a [ParametricEq] curve to the audio stream as a Media3 [AudioProcessor].
 *
 * Threading: [applyProfile] and [disable] are called from the UI or service thread; the
 * processing methods are called from the audio thread. They communicate only through the
 * immutable [Curve] published to the `@Volatile` [curve] field, so no lock is held on the
 * audio thread and a profile swap is always observed atomically.
 */
@UnstableApi
class ParametricEqualizerAudioProcessor : BaseAudioProcessor() {

    /**
     * An immutable, fully-resolved curve. Everything the audio thread needs is captured
     * here, so it never has to consult mutable state that another thread could be editing.
     */
    private class Curve(
        val filters: Array<BiquadFilter>,
        val preampGain: Double,
        val active: Boolean,
    ) {
        companion object {
            /** Unity pass-through. */
            val OFF = Curve(filters = emptyArray(), preampGain = 1.0, active = false)
        }
    }

    /** Set by [onConfigure]; read when building a curve. */
    @Volatile private var sampleRate: Int = 0

    /**
     * The configured channel count and encoding, cached here on purpose.
     *
     * BaseAudioProcessor's own `inputAudioFormat` field is only assigned by `flush()`, not
     * by `configure()` — its bytecode stores the result of onConfigure() into
     * `pendingOutputAudioFormat` and leaves `inputAudioFormat` at NOT_SET until a flush
     * happens. Reading `inputAudioFormat` in queueInput would therefore see encoding
     * ENCODING_INVALID (-1) and channels 0, fall through to the default branch, and silently
     * pass audio through unprocessed. Keeping our own copy makes the processor correct
     * whether or not a flush precedes the first buffer.
     */
    @Volatile private var configuredChannels: Int = 0
    @Volatile private var configuredEncoding: Int = C.ENCODING_INVALID

    /** The live curve. Read exactly once per buffer by [queueInput]. */
    @Volatile private var curve: Curve = Curve.OFF

    /**
     * The last profile handed to [applyProfile], retained so the coefficients can be
     * recomputed whenever the sink re-configures at a different sample rate.
     */
    @Volatile private var appliedProfile: ParametricEq? = null

    /** True when a non-flat curve is currently in force. */
    val isEnabled: Boolean
        get() = curve.active

    /**
     * Installs [eq]. Safe to call before the processor is configured: the curve is then
     * built as soon as the sample rate is known.
     */
    fun applyProfile(eq: ParametricEq) {
        appliedProfile = eq
        val rate = sampleRate
        if (rate == 0) {
            // Not configured yet: onConfigure() builds the curve once the rate is known.
            curve = Curve.OFF
            return
        }
        curve = buildCurve(eq, rate)
    }

    /** Returns to a unity pass-through. */
    fun disable() {
        appliedProfile = null
        curve = Curve.OFF
    }

    private fun buildCurve(eq: ParametricEq, rate: Int): Curve {
        if (eq.isFlat) return Curve.OFF

        val usable =
            eq.bands.filter { band ->
                band.enabled && BiquadFilter.isBandUsable(band.frequency, band.q, rate)
            }

        val filters =
            Array(usable.size) { index ->
                val band = usable[index]
                BiquadFilter(
                    sampleRate = rate,
                    frequency = band.frequency,
                    gain = band.gain,
                    q = band.q,
                    filterType = band.filterType,
                )
            }

        val preampGain = 10.0.pow(eq.preamp / 20.0).takeIf { it.isFinite() } ?: 1.0

        val active = filters.isNotEmpty() || abs(preampGain - 1.0) > 1e-9
        return Curve(filters = filters, preampGain = preampGain, active = active)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encodingSupported =
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val channelsSupported = inputAudioFormat.channelCount in 1..2

        // Anything we cannot process cleanly is bypassed rather than fatal. Returning
        // NOT_SET makes BaseAudioProcessor report isActive() == false, and Media3 drops
        // the processor from the pipeline entirely — no DSP, no allocation, no crash.
        if (!encodingSupported || !channelsSupported) {
            sampleRate = 0
            configuredChannels = 0
            configuredEncoding = C.ENCODING_INVALID
            curve = Curve.OFF
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRate = inputAudioFormat.sampleRate
        configuredChannels = inputAudioFormat.channelCount
        configuredEncoding = inputAudioFormat.encoding

        // A sample-rate change re-configures us mid-stream. Biquad coefficients depend on
        // the sample rate, so the curve MUST be rebuilt here from the retained profile —
        // otherwise the EQ would either be silently dropped or apply the wrong gains.
        val profile = appliedProfile
        curve = if (profile != null) buildCurve(profile, sampleRate) else Curve.OFF

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // One volatile read per buffer: the whole buffer is processed with one consistent
        // curve, even if the UI swaps the profile halfway through.
        val current = curve

        if (!current.active) {
            // Flat or disabled: copy straight through. No DSP, and replaceOutputBuffer
            // reuses its cached buffer, so this allocates nothing after the first buffer.
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val output = replaceOutputBuffer(remaining)
        when (configuredEncoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, output, current)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output, current)
            else -> output.put(inputBuffer)
        }
        output.flip()
    }

    private fun processInt16(input: ByteBuffer, output: ByteBuffer, current: Curve) {
        val filters = current.filters
        val preampGain = current.preampGain
        val channels = configuredChannels
        val frames = input.remaining() / 2 / channels

        var frame = 0
        while (frame < frames) {
            if (channels == 1) {
                var sample = input.getShort() / 32768.0
                for (index in filters.indices) sample = filters[index].processLeft(sample)
                sample *= preampGain
                // Clamp is the last line of defence against a boosted curve wrapping around.
                output.putShort((sample * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            } else {
                var left = input.getShort() / 32768.0
                var right = input.getShort() / 32768.0
                for (index in filters.indices) {
                    val filter = filters[index]
                    left = filter.processLeft(left)
                    right = filter.processRight(right)
                }
                left *= preampGain
                right *= preampGain
                output.putShort((left * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                output.putShort((right * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
            frame++
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer, current: Curve) {
        val filters = current.filters
        val preampGain = current.preampGain
        val channels = configuredChannels
        val frames = input.remaining() / 4 / channels

        var frame = 0
        while (frame < frames) {
            if (channels == 1) {
                var sample = input.getFloat().toDouble()
                for (index in filters.indices) sample = filters[index].processLeft(sample)
                sample *= preampGain
                output.putFloat(sample.coerceIn(-1.0, 1.0).toFloat())
            } else {
                var left = input.getFloat().toDouble()
                var right = input.getFloat().toDouble()
                for (index in filters.indices) {
                    val filter = filters[index]
                    left = filter.processLeft(left)
                    right = filter.processRight(right)
                }
                left *= preampGain
                right *= preampGain
                output.putFloat(left.coerceIn(-1.0, 1.0).toFloat())
                output.putFloat(right.coerceIn(-1.0, 1.0).toFloat())
            }
            frame++
        }
    }

    /** Called by BaseAudioProcessor on flush: drop the delay line so a seek is clean. */
    override fun onFlush() {
        val filters = curve.filters
        for (index in filters.indices) filters[index].reset()
    }

    override fun onReset() {
        sampleRate = 0
        configuredChannels = 0
        configuredEncoding = C.ENCODING_INVALID
        curve = Curve.OFF
        appliedProfile = null
    }
}
