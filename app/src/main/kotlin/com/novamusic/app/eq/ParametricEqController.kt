/*
 * Echo Music (2026) — https://github.com/EchoMusicApp/Echo-Music
 * Licensed under GPL-3.0 | see git history for contributors
 *
 * Ported from Echo Music:
 *   playback/src/main/kotlin/echo/music/iad1tya/eq/EqualizerService.kt
 * Adapted for NovaMusic: Echo's EqualizerService kept a list of processors registered from
 * the outside and had to buffer a "pending" profile until one showed up. Here the controller
 * creates the processors itself, so it always has a live one to configure and there is no
 * pending-profile dance. The controller also observes DataStore, which keeps every processor
 * in sync when the setting changes from anywhere in the app.
 *
 * One processor per audio sink, not one per app. NovaMusic's CrossfadeAudio builds a second
 * ExoPlayer (overlapPlayerFactory in MusicService) that runs at the same time as the primary
 * one during a crossfade. Handing both sinks the same processor instance would mean two
 * threads calling configure() and queueInput() on one set of biquad delay lines — the second
 * configure() would silently retune the first player, and the two streams would interleave
 * through shared filter state. Each sink therefore gets its own instance, and the controller
 * fans the current curve out to all of them.
 */

package com.novamusic.app.eq

import com.novamusic.app.eq.audio.ParametricEqualizerAudioProcessor
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqParser
import com.novamusic.app.eq.data.ParametricEqRepository
import com.novamusic.app.eq.data.ParametricEqState
import com.novamusic.app.eq.data.SavedParametricEqProfile
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
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
 * Owns the parametric EQ processors and keeps them in step with the stored curve.
 *
 * [MusicService] asks for one processor per audio sink via [createProcessor]; the settings UI
 * drives everything else through this class.
 */
@Singleton
class ParametricEqController
@Inject
constructor(
    private val repository: ParametricEqRepository,
) {
    /**
     * Every processor currently installed in an audio chain.
     *
     * Held weakly on purpose: the audio sink holds the only strong reference, so when a
     * player is released (the crossfade overlap player is created per transition) the entry
     * clears itself instead of pinning the player for the lifetime of the app.
     */
    private val processors =
        CopyOnWriteArrayList<WeakReference<ParametricEqualizerAudioProcessor>>()

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

    /**
     * A fresh processor for one audio sink, seeded with the curve that is in force right now.
     *
     * Call this once per `createRenderersFactory()`. The returned instance is tracked so
     * later profile changes reach it.
     */
    fun createProcessor(): ParametricEqualizerAudioProcessor {
        val processor = ParametricEqualizerAudioProcessor()
        processors.add(WeakReference(processor))
        // Seed from the current state so a sink created after the user changed a band does
        // not start silent, or worse, start on a stale curve.
        if (_enabled.value) processor.applyProfile(_curve.value) else processor.disable()
        return processor
    }

    /**
     * How many processors are still reachable by their audio sink.
     *
     * Exposed for tests: the invariant that matters is one registry entry per sink, because
     * [apply] walks the registry once and touches each entry a single time — so "one entry per
     * sink" is what makes a curve change land exactly once per sink.
     */
    internal fun liveProcessorCount(): Int = processors.count { it.get() != null }

    private fun apply(state: ParametricEqState) {        _enabled.value = state.enabled
        _curve.value = state.curve

        val iterator = processors.iterator()
        while (iterator.hasNext()) {
            val reference = iterator.next()
            val processor = reference.get()
            if (processor == null) {
                // The owning player is gone; drop the entry so the list cannot grow.
                processors.remove(reference)
                continue
            }
            if (state.enabled) {
                processor.applyProfile(state.curve)
            } else {
                processor.disable()
            }
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
