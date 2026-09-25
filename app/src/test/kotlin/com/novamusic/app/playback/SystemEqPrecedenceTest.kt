/*
 * NovaMusic — GPL-3.0.
 *
 * The parametric EQ is a second, optional equalizer. This pins the rule that decides what
 * happens when both it and the system equalizer are switched on: the parametric EQ wins, the
 * platform effects are driven with every stage off, and nothing the user has stored is lost.
 *
 * Pure unit test: effectiveSystemEqSettings() is a plain function over EqSettings, so no
 * device, no audio session and no platform Equalizer are involved.
 */

package com.novamusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEqPrecedenceTest {

    /** A system EQ the user has fully configured and switched on. */
    private val fullyEnabled =
        EqSettings(
            enabled = true,
            bandLevelsMb = listOf(300, 0, -200, 450),
            outputGainEnabled = true,
            outputGainMb = 150,
            bassBoostEnabled = true,
            bassBoostStrength = 700,
            virtualizerEnabled = true,
            virtualizerStrength = 400,
        )

    @Test
    fun `with the parametric EQ off, the stored system settings are applied unchanged`() {
        val applied = effectiveSystemEqSettings(fullyEnabled, parametricEqEnabled = false)

        assertSame("nothing should be copied or altered when the parametric EQ is off", fullyEnabled, applied)
        assertTrue(applied.enabled)
        assertTrue(applied.bassBoostEnabled)
        assertTrue(applied.virtualizerEnabled)
        assertTrue(applied.outputGainEnabled)
    }

    @Test
    fun `with both equalizers on, every system stage is applied disabled`() {
        val applied = effectiveSystemEqSettings(fullyEnabled, parametricEqEnabled = true)

        assertFalse("the system equalizer must not stack with the parametric one", applied.enabled)
        assertFalse(applied.bassBoostEnabled)
        assertFalse(applied.virtualizerEnabled)
        assertFalse(applied.outputGainEnabled)
    }

    @Test
    fun `with both on, the stored values are not lost, only not applied`() {
        val stored = fullyEnabled
        val applied = effectiveSystemEqSettings(stored, parametricEqEnabled = true)

        // The applied copy is muted...
        assertFalse(applied.enabled)
        assertFalse(applied.outputGainEnabled)
        // ...but the settings the user actually saved still hold everything.
        assertTrue("band levels must survive", stored.enabled)
        assertEquals(listOf(300, 0, -200, 450), stored.bandLevelsMb)
        assertEquals(150, stored.outputGainMb)
        assertEquals(700, stored.bassBoostStrength)
        assertEquals(400, stored.virtualizerStrength)
    }

    @Test
    fun `turning the parametric EQ back off restores the system EQ exactly`() {
        val whileParametricOn = effectiveSystemEqSettings(fullyEnabled, parametricEqEnabled = true)
        val afterParametricOff = effectiveSystemEqSettings(fullyEnabled, parametricEqEnabled = false)

        assertFalse(whileParametricOn.enabled)
        assertSame("the original settings must come straight back", fullyEnabled, afterParametricOff)
        assertTrue(afterParametricOff.enabled)
        assertTrue(afterParametricOff.bassBoostEnabled)
        assertTrue(afterParametricOff.virtualizerEnabled)
    }

    @Test
    fun `the rule is idempotent for an already-off system EQ`() {
        val alreadyOff = fullyEnabled.copy(enabled = false, bassBoostEnabled = false, virtualizerEnabled = false)

        val applied = effectiveSystemEqSettings(alreadyOff, parametricEqEnabled = true)

        assertFalse(applied.enabled)
        assertFalse(applied.bassBoostEnabled)
        assertFalse(applied.virtualizerEnabled)
    }

    @Test
    fun `only the four enable flags are muted, never the levels`() {
        val applied = effectiveSystemEqSettings(fullyEnabled, parametricEqEnabled = true)

        // The band levels and strengths ride along untouched; only the enable switches change.
        // This matters because they are written back to the effects as-is.
        assertEquals(fullyEnabled.bandLevelsMb, applied.bandLevelsMb)
        assertEquals(fullyEnabled.outputGainMb, applied.outputGainMb)
        assertEquals(fullyEnabled.bassBoostStrength, applied.bassBoostStrength)
        assertEquals(fullyEnabled.virtualizerStrength, applied.virtualizerStrength)
    }
}
