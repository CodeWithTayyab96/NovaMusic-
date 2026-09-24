/*
 * NovaMusic — GPL-3.0.
 *
 * Regression cover for a bug found during review: NovaMusic's CrossfadeAudio builds a second
 * ExoPlayer through the same renderers factory, so two audio sinks are alive at once. The
 * controller must hand each sink its own processor, and must still push curve changes to all
 * of them.
 *
 * Note on timing: a freshly created processor has not been given an audio format yet, so it
 * holds the curve as *pending* and reports isEnabled == false. That is the intended
 * behaviour — nothing can be filtered until the sink says what the format is. These tests
 * therefore configure a processor before asserting that it is active.
 */

package com.novamusic.app.eq

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.eq.audio.ParametricEqualizerAudioProcessor
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqBand
import com.novamusic.app.eq.data.ParametricEqRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParametricEqControllerTest {

    private lateinit var context: Context
    private lateinit var controller: ParametricEqController

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        controller = ParametricEqController(ParametricEqRepository(context))
    }

    @Test
    fun `each audio sink gets its own processor instance`() {
        val primary = controller.createProcessor()
        val overlap = controller.createProcessor()

        assertNotSame(
            "the crossfade overlap player must not share the primary player's processor",
            primary,
            overlap,
        )
    }

    @Test
    fun `a processor created after a change picks up the curve on configure`() =
        runBlocking {
            controller.setEnabled(true)
            controller.saveCurve(curve())
            awaitCondition("the controller must adopt the stored curve") { controller.enabled.value }

            val late = controller.createProcessor()
            // No format yet, so the curve can only be pending.
            assertFalse(late.isEnabled)

            late.configure(format())
            // Configured: the pending curve is applied.
            assertTrue("the seeded curve must land on configure", late.isEnabled)
        }

    @Test
    fun `a curve change reaches every processor that already exists`() =
        runBlocking {
            val primary = configured()
            val overlap = configured()

            controller.setEnabled(true)
            controller.saveCurve(curve())

            awaitCondition("both processors must pick up the curve") {
                primary.isEnabled && overlap.isEnabled
            }
            assertTrue(primary.isEnabled)
            assertTrue(overlap.isEnabled)
        }

    @Test
    fun `turning the mode off reaches every processor`() =
        runBlocking {
            val primary = configured()
            val overlap = configured()

            controller.setEnabled(true)
            controller.saveCurve(curve())
            awaitCondition("both processors must pick up the curve") {
                primary.isEnabled && overlap.isEnabled
            }

            controller.setEnabled(false)
            awaitCondition("both processors must return to pass-through") {
                !primary.isEnabled && !overlap.isEnabled
            }
            assertFalse(primary.isEnabled)
            assertFalse(overlap.isEnabled)
        }

    // ------------------------------------------------------------------ helpers

    private fun format() = AudioProcessor.AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT)

    private fun curve() =
        ParametricEq(preamp = 0.0, bands = listOf(ParametricEqBand(1000.0, gain = 6.0)))

    /** A processor that has been told its audio format, as a real sink would. */
    private fun configured(): ParametricEqualizerAudioProcessor =
        controller.createProcessor().apply { configure(format()) }

    /** The controller collects DataStore on Dispatchers.Default, so changes arrive async. */
    private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
