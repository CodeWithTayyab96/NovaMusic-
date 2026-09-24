/*
 * NovaMusic — AI translation settings.
 *
 * Built with the project's existing PreferenceEntry and Material 3 components, following the
 * secret-field pattern already used by LastFMSettings (password visual transformation, error
 * text in the error colour). No parallel settings framework.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import com.novamusic.app.ui.component.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.navigation.NavController
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.novamusic.app.ui.utils.backToMain
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novamusic.app.R
import com.novamusic.app.translation.OpenRouterTranslationProvider
import com.novamusic.app.translation.TranslationConfig
import com.novamusic.app.translation.TranslationContractException
import com.novamusic.app.translation.TranslationSettingsRepository
import com.novamusic.app.translation.messageRes
import com.novamusic.app.ui.component.PreferenceEntry
import kotlinx.coroutines.launch

/**
 * AI Translation settings.
 *
 * The repository is the only source of truth: the configuration is collected as state and never
 * copied into a second store, so a change here is visible to the next translation without
 * restarting the app.
 *
 * The decrypted API key is never rendered. The field shows a placeholder when configured and
 * only ever holds what the user is actively typing.
 *
 * The model is deliberately NOT editable: NovaMusic always targets the OpenRouter free pool.
 * `openrouter/auto` is a paid router and would silently break translation for anyone without
 * credit, so there is no field that can be set to it.
 */
@Composable
fun AiTranslationSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { TranslationSettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val config by repository.config.collectAsStateWithLifecycle(initialValue = TranslationConfig())

    var showKeyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    // Short-lived feedback only; never holds a key value.
    var message: String? by remember { mutableStateOf(null) }

    Column(modifier = modifier) {
        TopAppBar(
            title = { Text(stringResource(R.string.ai_translation)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            scrollBehavior = scrollBehavior,
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.openrouter_api_key)) },
            subtitle = {
                Text(
                    if (config.isConfigured) {
                        stringResource(R.string.api_key_configured)
                    } else {
                        stringResource(R.string.api_key_not_configured)
                    },
                )
            },
            onClick = { showKeyDialog = true },
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.test_connection)) },
            subtitle = {
                Text(if (isTestingConnection) stringResource(R.string.loading) else stringResource(R.string.test_connection))
            },
            onClick = {
                if (isTestingConnection) return@PreferenceEntry
                scope.launch {
                    isTestingConnection = true
                    message = null
                    // A throwaway provider: one tiny request, then discarded. It reads the
                    // current configuration, so it tests exactly the key the user just saved.
                    val result =
                        OpenRouterTranslationProvider(
                            configProvider = { repository.currentConfig() },
                        ).testConnection()
                    val failure = result.exceptionOrNull()
                    message =
                        when {
                            result.isSuccess -> context.getString(R.string.test_connection_success)
                            failure is TranslationContractException ->
                                context.getString(failure.error.messageRes())
                            else -> context.getString(R.string.test_connection_failed)
                        }
                    isTestingConnection = false
                }
            },
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.translation_target_language)) },
            subtitle = { Text(config.targetLanguage) },
            onClick = { showLanguageDialog = true },
        )

        // Disclosure, not fine print: translation sends the lyric text off-device.
        Text(
            text = stringResource(R.string.ai_translation_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (showKeyDialog) {
        SecretValueDialog(
            title = stringResource(R.string.openrouter_api_key),
            label = stringResource(R.string.openrouter_api_key),
            initialValue = "",
            placeholder =
                if (config.isConfigured) {
                    stringResource(R.string.api_key_configured)
                } else {
                    stringResource(R.string.api_key_not_configured)
                },
            canClear = config.isConfigured,
            onDismiss = { showKeyDialog = false },
            onSave = { typed ->
                scope.launch {
                    val ok = repository.setApiKey(typed)
                    showKeyDialog = false
                    message =
                        if (ok) {
                            context.getString(R.string.api_key_saved)
                        } else {
                            // setApiKey returned false: nothing was written, so an existing key is
                            // still intact. Say so rather than implying success.
                            context.getString(R.string.api_key_save_failed)
                        }
                }
            },
            onClear = {
                scope.launch {
                    repository.clearApiKey()
                    showKeyDialog = false
                    message = context.getString(R.string.api_key_cleared)
                }
            },
        )
    }

    if (showLanguageDialog) {
        SecretValueDialog(
            title = stringResource(R.string.translation_target_language),
            label = stringResource(R.string.translation_target_language),
            initialValue = config.targetLanguage,
            placeholder = config.targetLanguage,
            canClear = false,
            masked = false,
            onDismiss = { showLanguageDialog = false },
            onSave = { value ->
                scope.launch {
                    if (value.isNotBlank()) {
                        repository.setTargetLanguage(value)
                    }
                    showLanguageDialog = false
                }
            },
            onClear = {},
        )
    }
}

/**
 * Single-line value entry used for the language and — with [masked] — the API key.
 *
 * The value is held only while the dialog is open and is cleared on dismiss.
 */
@Composable
private fun SecretValueDialog(
    title: String,
    label: String,
    initialValue: String,
    placeholder: String,
    canClear: Boolean,
    masked: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                visualTransformation =
                    if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Text,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            if (canClear) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.clear))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
