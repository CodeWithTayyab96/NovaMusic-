/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.utils

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.novamusic.app.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
object PreferenceStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prefs = MutableStateFlow<Preferences?>(null)
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch {
                context.dataStore.data.collect { preferences ->
                    _prefs.value = preferences
                }
            }
        }
    }

    fun <T> get(key: Preferences.Key<T>): T? = _prefs.value?.get(key)

    fun launchEdit(
        dataStore: DataStore<Preferences>,
        block: MutablePreferences.() -> Unit,
    ) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs.block()
            }
        }
    }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? {
    // Credential keys are routed through the Keystore-backed encrypted store.
    if (SecureCredentialStore.isCredential(key.name)) {
        return SecureCredentialStore.readSecureCredentialSync(key) ?: PreferenceStore.get(key)
    }
    return PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            null
        } else {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1500) {
                    data.first()[key]
                }
            }
        }
}

operator fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T {
    // Credential keys are routed through the Keystore-backed encrypted store.
    if (SecureCredentialStore.isCredential(key.name)) {
        return SecureCredentialStore.readSecureCredentialSync(key)
            ?: PreferenceStore.get(key)
            ?: defaultValue
    }
    return PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            defaultValue
        } else {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1500) {
                    data.first()[key]
                } ?: defaultValue
            }
        }
}

suspend fun <T> DataStore<Preferences>.getAsync(key: Preferences.Key<T>): T? {
    if (SecureCredentialStore.isCredential(key.name)) {
        return SecureCredentialStore.readSecureCredentialSync(key) ?: PreferenceStore.get(key)
    }
    return data.first()[key]
}

suspend fun <T> DataStore<Preferences>.getAsync(
    key: Preferences.Key<T>,
    defaultValue: T,
): T {
    if (SecureCredentialStore.isCredential(key.name)) {
        return SecureCredentialStore.readSecureCredentialSync(key)
            ?: PreferenceStore.get(key)
            ?: defaultValue
    }
    return data.first()[key] ?: defaultValue
}

/**
 * Reactive equivalent of [get] for credential keys. Emits the credential value
 * preferring the encrypted [SecureCredentialStore] and falling back to the
 * plaintext DataStore until the one-time migration has removed it. Because the
 * flow is driven by DataStore emissions, any accompanying preference change
 * (e.g. PoToken/visitor data written during login) also re-resolves the
 * encrypted value, keeping consumers coherent after migration.
 */
fun <T> DataStore<Preferences>.credentialFlow(key: Preferences.Key<T>): Flow<T?> =
    data
        .map { prefs ->
            if (SecureCredentialStore.isCredential(key.name)) {
                SecureCredentialStore.readSecureCredentialSync(key) ?: prefs[key]
            } else {
                prefs[key]
            }
        }
        .distinctUntilChanged()

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    // Credential keys are routed through the Keystore-backed encrypted store
    // instead of the (unencrypted, backup-included) DataStore.
    if (SecureCredentialStore.isCredential(key.name)) {
        return rememberSecurePreference(key, defaultValue)
    }

    val context = LocalContext.current

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

/**
 * [rememberPreference] variant for Keystore-encrypted credential keys.
 * Reads/writes go through [SecureCredentialStore] (synchronous SharedPreferences
 * backed by AES-GCM with an Android Keystore key).
 */
@Composable
private fun <T> rememberSecurePreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val state =
        remember {
            mutableStateOf(
                SecureCredentialStore.readSecureCredentialSync(key) ?: defaultValue,
            )
        }

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    if (key.name == SecureCredentialStore.expiresAtKeyName) {
                        @Suppress("UNCHECKED_CAST")
                        SecureCredentialStore.putLong(key.name, value as Long)
                    } else {
                        SecureCredentialStore.putString(key.name, value.toString())
                    }
                    state.value = value
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value.name
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
