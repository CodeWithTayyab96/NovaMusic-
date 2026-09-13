/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.novamusic.app.db.InternalDatabase
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.utils.dataStore
import com.novamusic.app.utils.get
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

private class LazyCache(
    private val create: () -> SimpleCache,
) : Cache {
    private val lock = Any()
    @Volatile private var cache: SimpleCache? = null

    private fun delegate(): SimpleCache =
        cache ?: synchronized(lock) { cache ?: create().also { cache = it } }

    override fun addListener(key: String, listener: Cache.Listener) =
        delegate().addListener(key, listener)

    override fun removeListener(key: String, listener: Cache.Listener) =
        delegate().removeListener(key, listener)

    override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
        delegate().getCachedSpans(key)

    override fun getKeys(): NavigableSet<String> =
        TreeSet(delegate().keys)

    override fun getCacheSpace(): Long =
        delegate().cacheSpace

    override fun getUid(): Long =
        delegate().uid

    override fun getCachedLength(key: String, position: Long, length: Long): Long =
        delegate().getCachedLength(key, position, length)

    override fun getCachedBytes(key: String, position: Long, length: Long): Long =
        delegate().getCachedBytes(key, position, length)

    override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) =
        delegate().applyContentMetadataMutations(key, mutations)

    override fun getContentMetadata(key: String): ContentMetadata =
        delegate().getContentMetadata(key)

    override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
        delegate().startReadWrite(key, position, length)

    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? =
        delegate().startReadWriteNonBlocking(key, position, length)

    override fun startFile(key: String, position: Long, maxLength: Long): File =
        delegate().startFile(key, position, maxLength)

    override fun commitFile(file: File, length: Long) =
        delegate().commitFile(file, length)

    override fun releaseHoleSpan(holeSpan: CacheSpan) =
        delegate().releaseHoleSpan(holeSpan)

    override fun removeSpan(span: CacheSpan) =
        delegate().removeSpan(span)

    override fun removeResource(key: String) =
        delegate().removeResource(key)

    override fun isCached(key: String, position: Long, length: Long): Boolean =
        delegate().isCached(key, position, length)

    override fun release() =
        delegate().release()
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * Hard ceiling for the disposable streaming/playback cache. See
     * [providePlayerCache] for why this value and why it is no longer user-configurable.
     */
    private const val PLAYER_CACHE_MAX_BYTES = 256L * 1024 * 1024

    @Singleton
    @Provides
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): MusicDatabase = InternalDatabase.newInstance(context)

    @Singleton
    @Provides
    fun provideDatabaseProvider(
        @ApplicationContext context: Context,
    ): DatabaseProvider = StandaloneDatabaseProvider(context)

    @Singleton
    @Provides
    @PlayerCache
    fun providePlayerCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache {
        // Deliberately fixed and bounded. This cache exists only to make streaming
        // smooth — it avoids re-fetching when the user seeks back or replays a track.
        // It is NOT an offline library and must never behave like one.
        //
        // 256 MB is roughly 4-5 hours of ~128 kbps audio: comfortably more than a
        // listening session needs, yet far too small to act as a download store.
        // LeastRecentlyUsedCacheEvictor discards the oldest streamed data
        // automatically, so ordinary listening can never grow storage without bound.
        //
        // Previously this read MaxSongCacheSizeKey (default 1024 MB) and treated -1 as
        // "unlimited" via NoOpCacheEvictor — an unbounded cache that the removed Cache
        // screen then surfaced to users as if it were their offline music.
        return LazyCache {
            SimpleCache(
                context.filesDir.resolve("exoplayer"),
                LeastRecentlyUsedCacheEvictor(PLAYER_CACHE_MAX_BYTES),
                databaseProvider,
            )
        }
    }

    @Singleton
    @Provides
    @DownloadCache
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache =
        LazyCache {
            SimpleCache(context.filesDir.resolve("download"), NoOpCacheEvictor(), databaseProvider)
        }
}
