/*
 * NovaMusic — GPL-3.0.
 *
 * Requirement 7 of the parametric EQ port: the new EQ preferences must ride along in backup
 * and export. They do so without any change to the backup code, because they are ordinary
 * DataStore preferences of types the exporter already understands (Boolean, Float, String).
 *
 * This test proves that end to end, through the real exporter and restorer rather than a
 * re-implementation: write a curve, export settings to XML, wipe DataStore, restore, and
 * compare the curve that comes back.
 */

package com.novamusic.app.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.novamusic.app.constants.ParametricEqBandsJsonKey
import com.novamusic.app.constants.ParametricEqEnabledKey
import com.novamusic.app.constants.ParametricEqPreampDbKey
import com.novamusic.app.db.InternalDatabase
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.eq.data.FilterType
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqBand
import com.novamusic.app.eq.data.ParametricEqRepository
import com.novamusic.app.utils.dataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class BackupSettingsRoundTripTest {

    private lateinit var context: Context
    private lateinit var delegate: InternalDatabase
    private lateinit var viewModel: BackupRestoreViewModel

    /** A curve that exercises every persisted field, including a non-default filter type. */
    private val curve =
        ParametricEq(
            preamp = -4.5,
            bands =
                listOf(
                    ParametricEqBand(frequency = 105.0, gain = 5.5, q = 0.70, filterType = FilterType.PK),
                    ParametricEqBand(frequency = 60.0, gain = 4.0, q = 0.70, filterType = FilterType.LSC),
                    ParametricEqBand(frequency = 8000.0, gain = -3.0, q = 1.00, filterType = FilterType.HSC),
                ),
        )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        delegate =
            Room
                .inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        delegate.openHelper.writableDatabase
        viewModel = BackupRestoreViewModel(MusicDatabase(delegate))
    }

    @After
    fun tearDown() {
        runCatching { delegate.openHelper.close() }
    }

    @Test
    fun `the EQ curve survives export, a wiped DataStore, and restore`() =
        runBlocking {
            val repository = ParametricEqRepository(context)

            // 1. Put a curve in DataStore, exactly as the settings screen would.
            repository.saveCurve(curve)
            repository.setEnabled(true)
            val before = repository.state.first()
            assertTrue("precondition: the EQ should be on", before.enabled)
            assertEquals(3, before.curve.bands.size)

            // 2. Export settings through the real exporter.
            val xml = ByteArrayOutputStream()
            viewModel.writeSettingsToXml(context, xml)
            val exported = xml.toString(Charsets.UTF_8.name())
            assertTrue("the export should mention the bands", exported.contains(ParametricEqBandsJsonKey.name))
            assertTrue("the export should mention the enable flag", exported.contains(ParametricEqEnabledKey.name))
            assertTrue("the export should mention the preamp", exported.contains(ParametricEqPreampDbKey.name))

            // 3. Wipe the EQ state, as if this were a fresh install.
            context.dataStore.edit { prefs ->
                prefs.remove(ParametricEqBandsJsonKey)
                prefs.remove(ParametricEqPreampDbKey)
                prefs.remove(ParametricEqEnabledKey)
            }
            val wiped = repository.state.first()
            assertFalse("precondition: the wipe should have taken", wiped.enabled)
            assertTrue("precondition: the wipe should have taken", wiped.curve.bands.isEmpty())

            // 4. Restore through the real restorer.
            viewModel.restoreSettingsFromXml(context, ByteArrayInputStream(xml.toByteArray()))

            // 5. The curve must come back identical.
            val after = repository.state.first()
            assertTrue("the EQ should be on again", after.enabled)
            assertEquals("preamp", before.curve.preamp, after.curve.preamp, 1e-4)
            assertEquals("band count", before.curve.bands.size, after.curve.bands.size)
            before.curve.bands.forEachIndexed { index, expected ->
                val actual = after.curve.bands[index]
                assertEquals("band $index frequency", expected.frequency, actual.frequency, 1e-6)
                assertEquals("band $index gain", expected.gain, actual.gain, 1e-6)
                assertEquals("band $index q", expected.q, actual.q, 1e-6)
                assertEquals("band $index filter type", expected.filterType, actual.filterType)
            }
        }

    @Test
    fun `the restored curve is the one the processor would apply`() =
        runBlocking {
            val repository = ParametricEqRepository(context)
            repository.saveCurve(curve)
            repository.setEnabled(true)

            val xml = ByteArrayOutputStream()
            viewModel.writeSettingsToXml(context, xml)

            context.dataStore.edit { prefs ->
                prefs.remove(ParametricEqBandsJsonKey)
                prefs.remove(ParametricEqPreampDbKey)
                prefs.remove(ParametricEqEnabledKey)
            }

            viewModel.restoreSettingsFromXml(context, ByteArrayInputStream(xml.toByteArray()))

            // A repository read is what the controller feeds to the audio processor, so if this
            // is intact the EQ is genuinely restored, not merely present in the XML.
            val state = repository.state.first()
            assertEquals(curve.bands.size, state.curve.bands.size)
            assertFalse("a restored non-flat curve must not read as flat", state.curve.isFlat)
            assertEquals(-4.5, state.curve.preamp, 1e-4)
        }

    @Test
    fun `the OpenRouter key is still excluded from the export`() =
        runBlocking {
            // Regression guard: the EQ work must not have disturbed the exclusion the
            // translation work added.
            context.dataStore.edit { prefs ->
                prefs[com.novamusic.app.constants.OpenRouterApiKeyKey] = "sk-or-should-not-be-exported"
            }

            val xml = ByteArrayOutputStream()
            viewModel.writeSettingsToXml(context, xml)

            assertFalse(
                "the OpenRouter credential must never leave the device in a backup",
                xml.toString(Charsets.UTF_8.name()).contains("sk-or-should-not-be-exported"),
            )
        }
}
