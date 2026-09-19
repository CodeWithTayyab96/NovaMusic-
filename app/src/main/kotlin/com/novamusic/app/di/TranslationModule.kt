/*
 * NovaMusic — Hilt bindings for AI lyric translation.
 *
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.di

import android.content.Context
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.translation.LyricsTranslationStore
import com.novamusic.app.translation.OpenRouterTranslationProvider
import com.novamusic.app.translation.RoomLyricsTranslationStore
import com.novamusic.app.translation.TranslateLyricsUseCase
import com.novamusic.app.translation.TranslationConfigSource
import com.novamusic.app.translation.TranslationProvider
import com.novamusic.app.translation.TranslationSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the translation stack so UI code never constructs a provider or touches HTTP.
 *
 * The use case depends on the TranslationProvider interface, so swapping in another backend
 * means changing one @Provides here — no ViewModel or Composable changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object TranslationModule {
    @Provides
    @Singleton
    fun provideTranslationSettingsRepository(
        @ApplicationContext context: Context,
    ): TranslationSettingsRepository = TranslationSettingsRepository(context)

    @Provides
    @Singleton
    fun provideTranslationConfigSource(
        repository: TranslationSettingsRepository,
    ): TranslationConfigSource = repository

    @Provides
    @Singleton
    fun provideLyricsTranslationStore(
        database: MusicDatabase,
    ): LyricsTranslationStore = RoomLyricsTranslationStore(database)

    @Provides
    @Singleton
    fun provideTranslationProvider(
        configSource: TranslationConfigSource,
    ): TranslationProvider =
        OpenRouterTranslationProvider(
            configProvider = { configSource.current() },
        )

    @Provides
    @Singleton
    fun provideTranslateLyricsUseCase(
        configSource: TranslationConfigSource,
        provider: TranslationProvider,
        store: LyricsTranslationStore,
    ): TranslateLyricsUseCase = TranslateLyricsUseCase(configSource, provider, store)
}
