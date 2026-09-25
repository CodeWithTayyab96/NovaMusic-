/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/data/EQProfileRepository.kt
 * Adapted for NovaMusic: the storage backend changed from a private SharedPreferences file
 * ("nanosonic_eq_profiles") to NovaMusic's existing DataStore, so the data sits alongside
 * every other setting and rides along in export/restore for free. Room is not involved, as
 * required. Echo's unused `deviceModel` / `isActive` fields were dropped.
 */

package com.novamusic.app.eq.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.novamusic.app.constants.ParametricEqBandsJsonKey
import com.novamusic.app.constants.ParametricEqEnabledKey
import com.novamusic.app.constants.ParametricEqPreampDbKey
import com.novamusic.app.constants.ParametricEqProfilesJsonKey
import com.novamusic.app.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** A named curve the user has saved. */
@Serializable
data class SavedParametricEqProfile(
    val id: String,
    val name: String,
    val bands: List<ParametricEqBand> = emptyList(),
    val preamp: Double = 0.0,
    val isCustom: Boolean = false,
    val addedTimestamp: Long = 0L,
)

/** The live parametric EQ: whether it is on, and the curve it is applying. */
data class ParametricEqState(
    val enabled: Boolean,
    val curve: ParametricEq,
) {
    companion object {
        val OFF = ParametricEqState(enabled = false, curve = ParametricEq.FLAT)
    }
}

/**
 * Persists the parametric EQ curve and the user's saved profiles in DataStore.
 *
 * Nothing here touches Room. All reads are defensive: a corrupt or truncated JSON value
 * degrades to "no bands" rather than crashing playback on the next app start.
 */
@Singleton
class ParametricEqRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** The current on/off flag and curve, as a flow so the processor stays in sync. */
    val state: Flow<ParametricEqState> = context.dataStore.data.map(::readState)

    /** User-saved profiles, newest first. */
    val profiles: Flow<List<SavedParametricEqProfile>> =
        context.dataStore.data.map { prefs -> readProfiles(prefs) }

    private fun readState(prefs: Preferences): ParametricEqState {
        val bands = decodeBands(prefs[ParametricEqBandsJsonKey])
        val preamp = (prefs[ParametricEqPreampDbKey] ?: 0f).toDouble()
        return ParametricEqState(
            enabled = prefs[ParametricEqEnabledKey] ?: false,
            curve = ParametricEq(preamp = preamp, bands = bands),
        )
    }

    private fun readProfiles(prefs: Preferences): List<SavedParametricEqProfile> {
        val raw = prefs[ParametricEqProfilesJsonKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedParametricEqProfile>>(raw) }
            .getOrElse { error ->
                Timber.tag(TAG).w(error, "Stored EQ profiles could not be read; ignoring them")
                emptyList()
            }
            .sortedByDescending { it.addedTimestamp }
    }

    private fun decodeBands(raw: String?): List<ParametricEqBand> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ParametricEqBand>>(raw) }
            .getOrElse { error ->
                Timber.tag(TAG).w(error, "Stored EQ bands could not be read; starting flat")
                emptyList()
            }
            .take(ParametricEq.MAX_BANDS)
    }

    /** Turns the mode on or off without changing the curve. */
    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[ParametricEqEnabledKey] = enabled }
    }

    /** Replaces the stored curve. */
    suspend fun saveCurve(eq: ParametricEq) {
        val bandsJson = runCatching { json.encodeToString(eq.bands) }.getOrNull() ?: return
        context.dataStore.edit { prefs ->
            prefs[ParametricEqBandsJsonKey] = bandsJson
            prefs[ParametricEqPreampDbKey] = eq.preamp.toFloat()
        }
    }

    /** Saves (or replaces, by [SavedParametricEqProfile.id]) a named profile. */
    suspend fun saveProfile(profile: SavedParametricEqProfile) {
        val existing = readProfiles(context.dataStore.data.first())
        val updated = existing.filterNot { it.id == profile.id } + profile
        writeProfiles(updated)
    }

    suspend fun deleteProfile(profileId: String) {
        writeProfiles(readProfiles(context.dataStore.data.first()).filterNot { it.id == profileId })
    }

    private suspend fun writeProfiles(profiles: List<SavedParametricEqProfile>) {
        val encoded = runCatching { json.encodeToString(profiles) }.getOrNull() ?: return
        context.dataStore.edit { prefs -> prefs[ParametricEqProfilesJsonKey] = encoded }
    }

    /** Builds a profile id that is stable per name and unique per save. */
    fun newProfileId(name: String): String = "custom_${System.currentTimeMillis()}_${name.hashCode()}"

    private companion object {
        const val TAG = "ParametricEqRepository"
    }
}
