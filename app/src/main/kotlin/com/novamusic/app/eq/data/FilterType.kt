/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/data/FilterType.kt
 * Adapted for NovaMusic: package renamed, ktfmt formatting. No behavioural change.
 */

package com.novamusic.app.eq.data

import kotlinx.serialization.Serializable

/**
 * Biquad filter kinds, matching the EqualizerAPO-style naming used by Echo's profile parser.
 *
 * Only [PK], [LSC] and [HSC] are implemented by [com.novamusic.app.eq.audio.BiquadFilter].
 * [LPQ] and [HPQ] are accepted by the parser for file compatibility but fall back to a
 * peaking filter, exactly as Echo does — see the `else` branch in `calculateCoefficients()`.
 */
@Serializable
enum class FilterType {
    /** Peaking EQ. */
    PK,

    /** Low shelf. */
    LSC,

    /** High shelf. */
    HSC,

    /** Low pass (accepted, not implemented — falls back to PK). */
    LPQ,

    /** High pass (accepted, not implemented — falls back to PK). */
    HPQ,
}
