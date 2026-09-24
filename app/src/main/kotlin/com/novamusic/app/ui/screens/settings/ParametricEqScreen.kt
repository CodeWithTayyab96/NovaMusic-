/*
 * NovaMusic — GPL-3.0.
 *
 * The parametric EQ settings screen: a new, optional EQ mode that sits alongside the
 * existing system equalizer (which is untouched). The band editor, preamp, reset and the
 * profile import all come from Echo Music's parametric EQ feature; the presentation follows
 * NovaMusic's Material 3 components rather than Echo's iOS-inspired design language.
 */

package com.novamusic.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.novamusic.app.R
import com.novamusic.app.di.ParametricEqEntryPoint
import com.novamusic.app.eq.ParametricEqController
import com.novamusic.app.eq.data.ParametricEq
import com.novamusic.app.eq.data.ParametricEqBand
import com.novamusic.app.eq.readProfileText
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Frequencies a new band starts at, chosen to cover the audible range evenly. */
private val DEFAULT_FREQUENCIES = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

@Composable
private fun rememberParametricEqController(): ParametricEqController {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context, ParametricEqEntryPoint::class.java)
            .parametricEqController()
    }
}

/**
 * Full-width dialog rather than a navigation destination, so it opens from the same player
 * menu row as the existing equalizer without adding a route to NavigationBuilder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParametricEqDialog(onDismiss: () -> Unit) {
    val controller = rememberParametricEqController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val enabled by controller.enabled.collectAsState()
    val storedCurve by controller.curve.collectAsState()

    // Local copy so dragging a slider does not write to DataStore on every frame; the write
    // happens when the gesture finishes.
    var curve by remember { mutableStateOf(storedCurve) }
    var preamp by remember { mutableStateOf(storedCurve.preamp) }

    // Adopt changes made elsewhere (a restored backup, another screen).
    LaunchedEffect(storedCurve) { curve = storedCurve; preamp = storedCurve.preamp }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Everything below is suspend, so it runs in the screen's scope rather than in
            // the activity-result callback.
            scope.launch {
                // The read is off the main thread: a picked file can be up to the parser's cap.
                val content =
                    withContext(Dispatchers.IO) { readProfileText(context, uri) }.getOrElse { error ->
                        snackbarHostState.showSnackbar(
                            error.message ?: context.getString(R.string.parametric_eq_import_failed),
                        )
                        return@launch
                    }

                controller.importProfile(name = uri.lastPathSegment ?: "Imported", content = content)
                    .onSuccess { imported ->
                        curve = imported
                        preamp = imported.preamp
                        controller.saveCurve(imported)
                    }
                    .onFailure { error ->
                        snackbarHostState.showSnackbar(
                            error.message ?: context.getString(R.string.parametric_eq_import_failed),
                        )
                    }
            }
        }

    fun commit(newCurve: ParametricEq) {
        curve = newCurve
        preamp = newCurve.preamp
        scope.launch { controller.saveCurve(newCurve) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.parametric_eq)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.close), contentDescription = null)
                        }
                    },
                    actions = {
                        TextButton(onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                            Text(stringResource(R.string.parametric_eq_import))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.parametric_eq_enable),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { scope.launch { controller.setEnabled(it) } },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.parametric_eq_bypasses_system),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    LabelledSlider(
                        label = stringResource(R.string.parametric_eq_preamp),
                        valueText = "${preamp.roundToInt()} dB",
                        value = preamp.toFloat(),
                        range = -24f..24f,
                        onValueChange = { preamp = it.toDouble() },
                        onValueChangeFinished = { commit(curve.copy(preamp = preamp)) },
                    )
                }

                if (curve.bands.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.parametric_eq_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(curve.bands) { index, band ->
                    BandEditor(
                        index = index,
                        band = band,
                        onBandChange = { updated ->
                            commit(curve.copy(bands = curve.bands.toMutableList().also { it[index] = updated }))
                        },
                        onRemove = {
                            commit(curve.copy(bands = curve.bands.filterIndexed { i, _ -> i != index }))
                        },
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val used = curve.bands.map { it.frequency }.toSet()
                                val next = DEFAULT_FREQUENCIES.firstOrNull { it !in used }
                                    ?: (curve.bands.size * 100.0 + 100.0)
                                commit(curve.copy(bands = curve.bands + ParametricEqBand(frequency = next, gain = 0.0)))
                            },
                            enabled = curve.bands.size < ParametricEq.MAX_BANDS,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.add), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.parametric_eq_add_band))
                        }
                        OutlinedButton(
                            onClick = { commit(ParametricEq.FLAT) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.parametric_eq_reset))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandEditor(
    index: Int,
    band: ParametricEqBand,
    onBandChange: (ParametricEqBand) -> Unit,
    onRemove: () -> Unit,
) {
    var frequency by remember(band) { mutableStateOf(band.frequency) }
    var gain by remember(band) { mutableStateOf(band.gain) }
    var q by remember(band) { mutableStateOf(band.q) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.parametric_eq_band, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = band.enabled,
                    onCheckedChange = { onBandChange(band.copy(enabled = it)) },
                )
                IconButton(onClick = onRemove) {
                    Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.parametric_eq_remove_band))
                }
            }
            LabelledSlider(
                label = stringResource(R.string.parametric_eq_frequency),
                valueText = "${frequency.roundToInt()} Hz",
                value = frequency.toFloat(),
                range = 20f..20000f,
                onValueChange = { frequency = it.toDouble() },
                onValueChangeFinished = { onBandChange(band.copy(frequency = frequency)) },
            )
            LabelledSlider(
                label = stringResource(R.string.parametric_eq_gain),
                valueText = "${gain.roundToInt()} dB",
                value = gain.toFloat(),
                range = -24f..24f,
                onValueChange = { gain = it.toDouble() },
                onValueChangeFinished = { onBandChange(band.copy(gain = gain)) },
            )
            LabelledSlider(
                label = stringResource(R.string.parametric_eq_q),
                valueText = String.format("%.2f", q),
                value = q.toFloat(),
                range = 0.1f..10f,
                onValueChange = { q = it.toDouble() },
                onValueChangeFinished = { onBandChange(band.copy(q = q)) },
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}
