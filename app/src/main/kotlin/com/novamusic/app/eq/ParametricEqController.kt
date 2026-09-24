/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/EqualizerService.kt
 * Adapted for NovaMusic: Echo's EqualizerService kept a list of processors registered from
 * the outside and had to buffer a "pending" profile until one showed up. Here the processor
 * is owned by this controller, so there is exactly one instance, it always exists, and the
 * pending-profile dance is unnecessary. The controller also observes DataStore, which is
 * what keeps the processor in sync when the setting changes from anywhere else in the app.
 */

package com.novamusic.app.eq

import com.novamusic.app.eq.audio.ParametricEqualizerAudioProcessor
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqParser
import com.novamusic.app.eq.data.ParametricEqRepository
import com.novamusic.app.eq.data.ParametricEqState
import com.novamusic.app.eq.data.SavedParametricEqProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The single owner of the parametric EQ processor.
 *
 * [MusicService] puts [processor] into its audio chain; the settings UI drives everything
 * else through this class. The DataStore flow is collected here so a change made anywhere —
 * the settings screen, a restored backup — reaches the audio thread without the UI and the
 * service having to know about each other.
 */
@Singleton
class ParametricEqController
@Inject
constructor(
    private val repository: ParametricEqRepository,
) {
    /** The processor instance to install in the ExoPlayer audio chain. */
    val processor = ParametricEqualizerAudioProcessor()

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()) + Dispatchers.Default

    private val _enabled = MutableStateFlow(false)

    /** Mirrors the persisted flag, so callers can react without reading DataStore. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _curve = MutableStateFlow(ParametricEq.FLAT)

    /** The curve currently in force. */
    val curve: StateFlow<ParametricEq> = _curve.asStateFlow()

    /** Saved profiles, newest first. */
    val profiles: Flow<List<SavedParametricEqProfile>> = repository.profiles

    init {
        scope.launch {
            repository.state.collect(::apply)
        }
    }

    private fun apply(state: ParametricEqState) {
        _enabled.value = state.enabled
        _curve.value = state.curve
        if (state.enabled) {
            processor.applyProfile(state.curve)
        } else {
            processor.disable()
        }
    }

    suspend fun setEnabled(enabled: Boolean) = repository.setEnabled(enabled)

    suspend fun saveCurve(eq: ParametricEq) = repository.saveCurve(eq)

    suspend fun saveProfile(profile: SavedParametricEqProfile) = repository.saveProfile(profile)

    suspend fun deleteProfile(profileId: String) = repository.deleteProfile(profileId)

    fun newProfileId(name: String): String = repository.newProfileId(name)

    /**
     * Validates and installs an imported profile.
     *
     * The caller is responsible for having read the file through a size-capped stream (see
     * [readProfileText]); this only parses, validates and stores.
     */
    suspend fun importProfile(name: String, content: String): Result<ParametricEq> {
        val parsed = ParametricEqParser.parse(content)
        parsed.onSuccess { eq ->
            repository.saveProfile(
                SavedParametricEqProfile(
                    id = repository.newProfileId(name),
                    name = name,
                    bands = eq.bands,
                    preamp = eq.preamp,
                    isCustom = true,
                    addedTimestamp = System.currentTimeMillis(),
                ),
            )
        }
        return parsed
    }
}
