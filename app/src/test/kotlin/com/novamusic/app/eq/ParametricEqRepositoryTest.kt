/*
 * NovaMusic — GPL-3.0.
 *
 * The parametric EQ stores its curve and profiles in DataStore, not Room, and the whole
 * point of that choice is that the values survive a restart. These tests exercise the real
 * DataStore instance the app uses, through Robolectric.
 */

package com.novamusic.app.eq

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.constants.ParametricEqBandsJsonKey
import com.novamusic.app.eq.data.FilterType
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqBand
import com.novamusic.app.eq.data.ParametricEqRepository
import com.novamusic.app.eq.data.SavedParametricEqProfile
import com.novamusic.app.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParametricEqRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: ParametricEqRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = ParametricEqRepository(context)
    }

    @Test
    fun `a curve survives a save and reload`() =
        runBlocking {
            val curve =
                ParametricEq(
                    preamp = -3.5,
                    bands =
                        listOf(
                            ParametricEqBand(frequency = 105.0, gain = 5.5, q = 0.70, filterType = FilterType.PK),
                            ParametricEqBand(frequency = 60.0, gain = 4.0, q = 0.70, filterType = FilterType.LSC),
                        ),
                )

            repository.saveCurve(curve)
            repository.setEnabled(true)

            val state = repository.state.first()
            assertTrue(state.enabled)
            // The preamp is persisted as a Float, so compare with Float precision.
            assertEquals(-3.5, state.curve.preamp, 1e-4)
            assertEquals(2, state.curve.bands.size)
            assertEquals(105.0, state.curve.bands[0].frequency, 1e-6)
            assertEquals(5.5, state.curve.bands[0].gain, 1e-6)
            assertEquals(0.70, state.curve.bands[0].q, 1e-6)
            assertEquals(FilterType.LSC, state.curve.bands[1].filterType)
        }

    @Test
    fun `turning the mode off keeps the curve`() =
        runBlocking {
            val curve = ParametricEq(preamp = 0.0, bands = listOf(ParametricEqBand(1000.0, 3.0)))

            repository.saveCurve(curve)
            repository.setEnabled(true)
            repository.setEnabled(false)

            val state = repository.state.first()
            assertFalse(state.enabled)
            assertEquals(1, state.curve.bands.size)
            assertEquals(1000.0, state.curve.bands[0].frequency, 1e-6)
        }

    @Test
    fun `a saved profile survives a reload`() =
        runBlocking {
            val profile =
                SavedParametricEqProfile(
                    id = "profile-under-test",
                    name = "Bass lift",
                    bands = listOf(ParametricEqBand(60.0, 4.0, 0.7, FilterType.LSC)),
                    preamp = -2.0,
                    isCustom = true,
                    addedTimestamp = 1_000L,
                )

            repository.saveProfile(profile)

            val loaded = repository.profiles.first().firstOrNull { it.id == "profile-under-test" }
            assertEquals("Bass lift", loaded?.name)
            assertEquals(1, loaded?.bands?.size)
            assertEquals(FilterType.LSC, loaded?.bands?.first()?.filterType)
            assertEquals(-2.0, loaded?.preamp ?: 0.0, 1e-6)
        }

    @Test
    fun `deleting a profile removes it and leaves the others`() =
        runBlocking {
            repository.saveProfile(
                SavedParametricEqProfile(id = "keep", name = "Keep", addedTimestamp = 1L, isCustom = true),
            )
            repository.saveProfile(
                SavedParametricEqProfile(id = "drop", name = "Drop", addedTimestamp = 2L, isCustom = true),
            )

            repository.deleteProfile("drop")

            val ids = repository.profiles.first().map { it.id }
            assertTrue(ids.contains("keep"))
            assertFalse(ids.contains("drop"))
        }

    @Test
    fun `saving the same profile id replaces it instead of duplicating`() =
        runBlocking {
            repository.saveProfile(
                SavedParametricEqProfile(id = "same", name = "First", addedTimestamp = 1L, isCustom = true),
            )
            repository.saveProfile(
                SavedParametricEqProfile(id = "same", name = "Second", addedTimestamp = 2L, isCustom = true),
            )

            val matching = repository.profiles.first().filter { it.id == "same" }
            assertEquals(1, matching.size)
            assertEquals("Second", matching.first().name)
        }

    @Test
    fun `a corrupt stored value degrades to a flat curve instead of crashing`() =
        runBlocking {
            context.dataStore.edit { prefs -> prefs[ParametricEqBandsJsonKey] = "{ this is not json" }

            val state = repository.state.first()
            assertTrue("a bad value must not take the app down", state.curve.bands.isEmpty())
            assertEquals(0.0, state.curve.preamp, 1e-6)
        }

    @Test
    fun `stored bands are capped at the maximum`() =
        runBlocking {
            val tooMany =
                (1..40).map { ParametricEqBand(frequency = it * 100.0, gain = 1.0) }
            repository.saveCurve(ParametricEq(preamp = 0.0, bands = tooMany))

            val state = repository.state.first()
            assertTrue(state.curve.bands.size <= ParametricEq.MAX_BANDS)
        }
}
