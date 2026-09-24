/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/data/ParametricEQParser.kt
 * Adapted for NovaMusic:
 *   - a hard byte cap for imported files ([MAX_PROFILE_BYTES]); Echo read whatever it was
 *     handed with File.readText(), which is unbounded
 *   - [parse] returns Result with a message that names the problem, so the UI can show
 *     something specific instead of a stack trace
 *   - [readAndParse] takes text that the caller has already read through a size-capped
 *     stream, so the Storage Access Framework path never buffers an unbounded file
 *   - logs through Timber, not android.util.Log
 */

package com.novamusic.app.eq.data

import timber.log.Timber

/**
 * Parses EqualizerAPO-style parametric EQ profile files.
 *
 * Supported shape (the same one Echo accepted):
 * ```
 * Preamp: -6.0 dB
 * Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain 4.0 dB Q 0.70
 * ```
 * Lines that are not `Preamp:` or `Filter …` are kept verbatim in
 * [ParametricEq.metadata] so an import/export round trip loses nothing.
 */
object ParametricEqParser {

    /**
     * Largest profile we will read. Real profiles are a few hundred bytes; 256 KiB is far
     * beyond any legitimate file and keeps a malformed or hostile picker result bounded.
     */
    const val MAX_PROFILE_BYTES = 256 * 1024

    /** How many bands we will parse before giving up, to bound work on a crafted file. */
    private const val MAX_PARSED_LINES = 2_000

    private val PREAMP_REGEX =
        Regex("""Preamp:\s*([-+]?\d+\.?\d*)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER_LINE_REGEX = Regex("""^Filter\s*\d+\s*:""", RegexOption.IGNORE_CASE)

    /**
     * Parses [content] and validates it.
     *
     * @return the parsed curve, or a failure whose message is safe to show to a user.
     */
    fun parse(content: String): Result<ParametricEq> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("The file is empty."))
        }
        if (content.length > MAX_PROFILE_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "The profile is larger than ${MAX_PROFILE_BYTES / 1024} KiB and was not read.",
                ),
            )
        }

        val eq =
            try {
                parseText(content)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse EQ profile")
                return Result.failure(IllegalArgumentException("The file could not be read as an EQ profile."))
            }

        if (eq.bands.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "No filter bands were found. Expected lines like " +
                        "\"Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70\".",
                ),
            )
        }

        val errors = validate(eq)
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(errors.first()))
        }

        return Result.success(eq)
    }

    /** Parses without validating. Kept for parity with Echo's API and for unit tests. */
    fun parseText(content: String): ParametricEq {
        var preamp = 0.0
        val bands = mutableListOf<ParametricEqBand>()
        val metadata = mutableMapOf<String, String>()
        var examined = 0

        for (line in content.lineSequence()) {
            if (++examined > MAX_PARSED_LINES) break

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("Preamp:", ignoreCase = true) -> preamp = parsePreamp(trimmed)

                FILTER_LINE_REGEX.containsMatchIn(trimmed) -> {
                    parseFilterLine(trimmed)?.let { band ->
                        if (bands.size < ParametricEq.MAX_BANDS) bands.add(band)
                    }
                }

                else -> {
                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size == 2) metadata[parts[0].trim()] = parts[1].trim()
                }
            }
        }

        return ParametricEq(preamp = preamp, bands = bands, metadata = metadata)
    }

    private fun parsePreamp(line: String): Double =
        PREAMP_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0

    /**
     * A band is only accepted when the line is switched on and carries a filter type,
     * a centre frequency, a gain and a Q. Anything else is skipped, matching Echo.
     */
    private fun parseFilterLine(line: String): ParametricEqBand? {
        if (!line.contains("ON", ignoreCase = true)) return null

        val filterType = parseFilterType(line) ?: return null
        val frequency = parseValue(line, "Fc", "Hz") ?: return null
        val gain = parseValue(line, "Gain", "dB") ?: return null
        val q = parseValue(line, "Q", null) ?: return null

        return ParametricEqBand(
            filterType = filterType,
            frequency = frequency,
            gain = gain,
            q = q,
        )
    }

    private fun parseFilterType(line: String): FilterType? =
        when {
            line.contains("LSC", ignoreCase = true) -> FilterType.LSC
            line.contains("HSC", ignoreCase = true) -> FilterType.HSC
            line.contains("PK", ignoreCase = true) -> FilterType.PK
            line.contains("LPQ", ignoreCase = true) -> FilterType.LPQ
            line.contains("HPQ", ignoreCase = true) -> FilterType.HPQ
            else -> null
        }

    private fun parseValue(line: String, keyword: String, unit: String?): Double? {
        val unitPattern = if (unit != null) "\\s*$unit" else ""
        val regex = Regex("""$keyword\s+([-+]?\d+\.?\d*)$unitPattern""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    /** Renders [eq] in the same format [parseText] accepts. */
    fun toFileFormat(eq: ParametricEq): String = buildString {
        appendLine("Preamp: ${eq.preamp} dB")
        eq.bands.forEachIndexed { index, band ->
            appendLine(
                "Filter ${index + 1}: ON ${band.filterType} " +
                    "Fc ${band.frequency.toInt()} Hz " +
                    "Gain ${band.gain} dB " +
                    "Q ${String.format("%.2f", band.q)}",
            )
        }
    }

    /** @return human-readable problems; empty when [eq] is acceptable. */
    fun validate(eq: ParametricEq): List<String> {
        val errors = mutableListOf<String>()

        if (eq.preamp < -50.0 || eq.preamp > 50.0) {
            errors.add("The preamp (${eq.preamp} dB) must be between -50 and +50 dB.")
        }
        if (eq.bands.isEmpty()) {
            errors.add("An EQ profile needs at least one band.")
        }
        if (eq.bands.size > ParametricEq.MAX_BANDS) {
            errors.add("The profile has ${eq.bands.size} bands; the maximum is ${ParametricEq.MAX_BANDS}.")
        }

        eq.bands.forEachIndexed { index, band ->
            if (!band.frequency.isFinite() || band.frequency <= 0.0 || band.frequency > 100_000.0) {
                errors.add("Band ${index + 1}: frequency ${band.frequency} Hz must be between 1 and 100000 Hz.")
            }
            if (!band.gain.isFinite() || band.gain < -30.0 || band.gain > 30.0) {
                errors.add("Band ${index + 1}: gain ${band.gain} dB must be between -30 and +30 dB.")
            }
            if (!band.q.isFinite() || band.q <= 0.0 || band.q > 20.0) {
                errors.add("Band ${index + 1}: Q ${band.q} must be between 0.01 and 20.")
            }
        }

        return errors
    }

    private const val TAG = "ParametricEqParser"
}
