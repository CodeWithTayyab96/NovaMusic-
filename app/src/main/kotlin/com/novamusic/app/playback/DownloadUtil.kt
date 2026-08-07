/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.playback

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.novamusic.app.innertube.YouTube
import com.novamusic.app.innertube.models.YouTubeClient
import com.novamusic.app.constants.AudioQuality
import com.novamusic.app.constants.AudioQualityKey
import com.novamusic.app.constants.PlayerStreamClient
import com.novamusic.app.constants.PlayerStreamClientKey
import com.novamusic.app.db.MusicDatabase
import com.novamusic.app.db.entities.FormatEntity
import com.novamusic.app.db.entities.SongEntity
import com.novamusic.app.di.DownloadCache
import com.novamusic.app.di.PlayerCache
import com.novamusic.app.utils.YTPlayerUtils
import com.novamusic.app.utils.StreamClientUtils
import com.novamusic.app.utils.enumPreference
import com.novamusic.app.constants.NetworkMeteredKey
import com.novamusic.app.utils.dataStore
import com.novamusic.app.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext appContext: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
) {
    private val connectivityManager = appContext.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(appContext, AudioQualityKey, AudioQuality.AUTO)
    private val preferredStreamClient by enumPreference(appContext, PlayerStreamClientKey, PlayerStreamClient.ANDROID_VR)
    private val settingsDataStore = appContext.dataStore
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
    private val avoidStreamCodecs: Set<String> by lazy {
        if (deviceSupportsMimeType("audio/opus")) emptySet() else setOf("opus")
    }
    val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isYouTubeMediaHost =
                    host.endsWith("googlevideo.com") ||
                        host.endsWith("googleusercontent.com") ||
                        host.endsWith("youtube.com") ||
                        host.endsWith("youtube-nocookie.com") ||
                        host.endsWith("ytimg.com")

                if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                val clientParam = request.url.queryParameter("c")?.trim().orEmpty()

                val userAgent = StreamClientUtils.resolveUserAgent(clientParam)
                val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

                val builder = request.newBuilder().header("User-Agent", userAgent)
                originReferer.origin?.let { builder.header("Origin", it) }
                originReferer.referer?.let { builder.header("Referer", it) }

                chain.proceed(builder.build())
            }.build()
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        mediaOkHttpClient,
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.cacheSpace > 500 * 1024 * 1024L) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    playerCache.keys.shuffled().take(10).forEach { key ->
                        playerCache.getCachedSpans(key).sumOf { it.length }
                    }
                }
            }
            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }
            val streamUrl = runBlocking(Dispatchers.IO) { resolveStreamUrl(mediaId) }
            dataSpec.withUri(streamUrl.toUri())
        }

    /**
     * Resolves the direct stream URL for [mediaId], caching the player response and the
     * resolved URL so repeat calls (playback + download) are cheap.
     * Used by both the playback data source and [LocalFileDownloader].
     */
    suspend fun resolveStreamUrl(mediaId: String): String {
        songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
            return it.first
        }
        val networkMeteredPref = settingsDataStore.get(NetworkMeteredKey, true)
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            mediaId,
            audioQuality = audioQuality,
            preferredStreamClient = preferredStreamClient,
            connectivityManager = connectivityManager,
            networkMetered = networkMeteredPref,
            avoidCodecs = avoidStreamCodecs,
        ).getOrThrow()
        val format = playbackData.format

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength!!,
                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                    perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                ),
            )

            val now = LocalDateTime.now()
            val existing = getSongByIdBlocking(mediaId)?.song

            val updatedSong = if (existing != null) {
                if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
            } else {
                SongEntity(
                    id = mediaId,
                    title = playbackData.videoDetails?.title ?: "Unknown",
                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                    thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                    dateDownload = now
                )
            }

            upsert(updatedSong)
        }

        val streamUrl = playbackData.streamUrl

        songUrlCache[mediaId] = streamUrl to (System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L))
        return streamUrl
    }

    val downloadNotificationHelper =
        DownloadNotificationHelper(appContext, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            appContext,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }
                    }
                }
            )
        }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val result = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
            downloads.value = result
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    private fun deviceSupportsMimeType(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
