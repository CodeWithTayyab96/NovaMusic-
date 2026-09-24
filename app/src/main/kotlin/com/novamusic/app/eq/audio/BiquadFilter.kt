/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/audio/BiquadFilter.kt
 * Adapted for NovaMusic: package renamed, ktfmt formatting, allocation-free stereo
 * processing (Echo returned a `Pair` per sample, which allocated in the audio thread),
 * non-finite guard, and Nyquist/range hardening.
 *
 * The coefficient formulas are the standard RBJ biquad equations from Robert
 * Bristow-Johnson's "Cookbook formulae for audio EQ biquad filter coefficients"
 * (https://www.w3.org/TR/audio-eq-cookbook/), the same reference Echo's implementation
 * follows. They are public-domain reference formulae, not code copied from another project.
 */

package com.novamusic.app.eq.audio

import com.novamusic.app.eq.data.FilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single direct-form-I biquad section, with independent state for the left and right
 * channels so one instance can process a stereo stream.
 *
 * Every method here is allocation-free and must only be called from the audio thread.
 * Coefficients are computed once at construction and never change afterwards, which is what
 * makes publishing a fresh filter array from another thread safe (see
 * [ParametricEqualizerAudioProcessor]).
 */
internal class BiquadFilter(
    private val sampleRate: Int,
    private val frequency: Double,
    private val gain: Double,
    private val q: Double = 1.41,
    private val filterType: FilterType = FilterType.PK,
) {

    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    /** False when the coefficients are degenerate; the filter is then skipped entirely. */
    val isUsable: Boolean

    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0

    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    init {
        calculateCoefficients()
        isUsable =
            b0.isFinite() &&
                b1.isFinite() &&
                b2.isFinite() &&
                a1.isFinite() &&
                a2.isFinite() &&
                (b0 != 0.0 || b1 != 0.0 || b2 != 0.0)
    }

    private fun calculateCoefficients() {
        when (filterType) {
            FilterType.LSC -> calculateLowShelfCoefficients()
            FilterType.HSC -> calculateHighShelfCoefficients()
            // PK, and the unimplemented LPQ/HPQ, all fall back to peaking — as in Echo.
            else -> calculatePeakingCoefficients()
        }
    }

    private fun calculatePeakingCoefficients() {
        val a = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        b0 = 1.0 + alpha * a
        b1 = -2.0 * cosOmega
        b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        a1 = -2.0 * cosOmega
        a2 = 1.0 - alpha / a

        normalise(a0)
    }

    private fun calculateLowShelfCoefficients() {
        val a = sqrt(10.0.pow(gain / 20.0))
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val s = 1.0
        val alpha = sinOmega / 2.0 * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
        val sqrtA = sqrt(a)

        val aPlusOne = a + 1.0
        val aMinusOne = a - 1.0
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        b0 = a * (aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha)
        b1 = 2.0 * a * (aMinusOne - aPlusOne * cosOmega)
        b2 = a * (aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha)
        val a0 = aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha
        a1 = -2.0 * (aMinusOne + aPlusOne * cosOmega)
        a2 = aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha

        normalise(a0)
    }

    private fun calculateHighShelfCoefficients() {
        val a = sqrt(10.0.pow(gain / 20.0))
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val s = 1.0
        val alpha = sinOmega / 2.0 * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
        val sqrtA = sqrt(a)

        val aPlusOne = a + 1.0
        val aMinusOne = a - 1.0
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        b0 = a * (aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha)
        b1 = -2.0 * a * (aMinusOne + aPlusOne * cosOmega)
        b2 = a * (aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha)
        val a0 = aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha
        a1 = 2.0 * (aMinusOne - aPlusOne * cosOmega)
        a2 = aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha

        normalise(a0)
    }

    /** Divides through by a0 so the recurrence needs no division per sample. */
    private fun normalise(a0: Double) {
        if (a0 == 0.0 || !a0.isFinite()) {
            // Degenerate (e.g. an extreme Q). Emit a pass-through so the filter is inert.
            b0 = 1.0
            b1 = 0.0
            b2 = 0.0
            a1 = 0.0
            a2 = 0.0
            return
        }
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
    }

    /** Mono, and the left channel of a stereo pair. */
    fun processLeft(input: Double): Double {
        val output = b0 * input + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        if (output.isFinite()) {
            x2L = x1L
            x1L = input
            y2L = y1L
            y1L = output
            return output
        }
        // A non-finite result means the state has blown up (denormal, overflow, bad
        // coefficient). Clear it so the NaN cannot latch and poison the rest of the track.
        x1L = 0.0
        x2L = 0.0
        y1L = 0.0
        y2L = 0.0
        return 0.0
    }

    /** The right channel of a stereo pair. */
    fun processRight(input: Double): Double {
        val output = b0 * input + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        if (output.isFinite()) {
            x2R = x1R
            x1R = input
            y2R = y1R
            y1R = output
            return output
        }
        x1R = 0.0
        x2R = 0.0
        y1R = 0.0
        y2R = 0.0
        return 0.0
    }

    /** Clears the delay line. Called on flush so a seek does not carry state across. */
    fun reset() {
        x1L = 0.0
        x2L = 0.0
        y1L = 0.0
        y2L = 0.0
        x1R = 0.0
        x2R = 0.0
        y1R = 0.0
        y2R = 0.0
    }

    companion object {
        /** True when [band] is worth building a filter for at this sample rate. */
        fun isBandUsable(frequency: Double, q: Double, sampleRate: Int): Boolean =
            frequency > 0.0 &&
                frequency < sampleRate / 2.0 &&
                q > 0.0 &&
                sampleRate > 0 &&
                abs(frequency).isFinite()
    }
}
