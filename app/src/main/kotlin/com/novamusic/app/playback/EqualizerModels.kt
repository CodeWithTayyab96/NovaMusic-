/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val bandCenterFreqHz: List<Int> = emptyList(),
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainMb: Int = 0,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
)

@Serializable
data class EqProfilesPayload(
    @SerialName("profiles")
    val profiles: List<EqProfile> = emptyList(),
)

data class EqCapabilities(
    val bandCount: Int,
    val minBandLevelMb: Int,
    val maxBandLevelMb: Int,
    val centerFreqHz: List<Int>,
    val systemPresets: List<String>,
)

data class EqSettings(
    val enabled: Boolean,
    val bandLevelsMb: List<Int>,
    val outputGainEnabled: Boolean,
    val outputGainMb: Int,
    val bassBoostEnabled: Boolean,
    val bassBoostStrength: Int,
    val virtualizerEnabled: Boolean,
    val virtualizerStrength: Int,
)

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

/**
 * The system-equalizer settings that should actually be applied to the platform effects.
 *
 * The parametric EQ is a separate, optional mode and it takes precedence. While it is on, this
 * returns a copy of [stored] with every stage switched off, so the two equalizers cannot stack:
 * the platform effects stay attached to the session but do nothing.
 *
 * [stored] is never modified — this decides only what is *applied*, never what is *kept* — so
 * switching the parametric EQ back off restores the user's system EQ exactly as they left it,
 * including the band levels, bass boost, virtualizer and output gain they had saved.
 *
 * Pure and side-effect free, so the precedence rule is testable without a device and without a
 * platform [android.media.audiofx.Equalizer].
 */
internal fun effectiveSystemEqSettings(
    stored: EqSettings,
    parametricEqEnabled: Boolean,
): EqSettings =
    if (!parametricEqEnabled) {
        stored
    } else {
        stored.copy(
            enabled = false,
            outputGainEnabled = false,
            bassBoostEnabled = false,
            virtualizerEnabled = false,
        )
    }

