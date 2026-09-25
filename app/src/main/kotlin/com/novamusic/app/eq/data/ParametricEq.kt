/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/data/ParametricEQ.kt
 * Adapted for NovaMusic: package renamed, ktfmt formatting, added [ParametricEq.isFlat].
 */

package com.novamusic.app.eq.data

import kotlinx.serialization.Serializable

/** One band of a parametric EQ: a centre frequency, a gain in dB, a Q and a filter kind. */
@Serializable
data class ParametricEqBand(
    val frequency: Double,
    val gain: Double,
    val q: Double = 1.41,
    val filterType: FilterType = FilterType.PK,
    val enabled: Boolean = true,
)

/**
 * A full parametric EQ curve: a preamp in dB plus up to [MAX_BANDS] bands.
 *
 * [metadata] carries any `Key: value` lines the source profile file had that we do not
 * understand, so an imported EqualizerAPO file round-trips without losing information.
 */
@Serializable
data class ParametricEq(
    val preamp: Double,
    val bands: List<ParametricEqBand>,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        const val MAX_BANDS = 20

        /** Gains within this many dB of zero are treated as "no change". */
        private const val FLAT_EPSILON_DB = 0.01

        /** An EQ that does nothing: no bands, no preamp. */
        val FLAT = ParametricEq(preamp = 0.0, bands = emptyList())
    }

    /**
     * True when applying this EQ would be a no-op: every band is disabled or has a
     * negligible gain, and the preamp is unity.
     *
     * The audio processor uses this to take a zero-processing pass-through path, which is
     * what keeps a disabled or flat EQ free of both DSP work and buffer allocation.
     */
    val isFlat: Boolean
        get() =
            kotlin.math.abs(preamp) < FLAT_EPSILON_DB &&
                bands.none { it.enabled && kotlin.math.abs(it.gain) >= FLAT_EPSILON_DB }
}
