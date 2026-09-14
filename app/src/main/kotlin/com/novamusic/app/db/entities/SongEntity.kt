/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import com.novamusic.app.utils.LOCAL_SONG_ID_PREFIX
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.novamusic.app.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Immutable
@Entity(
    tableName = "song",
    indices = [
        Index(
            value = ["albumId"]
        )
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val duration: Int = -1, // in seconds
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val explicit: Boolean = false,
    val year: Int? = null,
    val date: LocalDateTime? = null, // ID3 tag property
    val dateModified: LocalDateTime? = null, // file property
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val totalPlayTime: Long = 0, // in milliseconds
    val inLibrary: LocalDateTime? = null,
    // Set only when the user explicitly downloads the song. It used to default to
    // LocalDateTime.now(), which stamped EVERY song with a download date the moment
    // it entered the database — including songs that were merely streamed once.
    val dateDownload: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "0")
    val isLocal: Boolean = false,
    @ColumnInfo(name = "localPath")
    val localPath: String? = null
) {
    fun localToggleLike() = copy(
        liked = !liked,
        likedDate = if (!liked) LocalDateTime.now() else null,
    )

    fun toggleLike(): SongEntity {
        // Local on-device songs have no YouTube video to like — just flip the flag.
        if (isLocal) return localToggleLike()

        return copy(
            liked = !liked,
            likedDate = if (!liked) LocalDateTime.now() else null,
            inLibrary = if (!liked) inLibrary ?: LocalDateTime.now() else inLibrary
        ).also {
            CoroutineScope(Dispatchers.IO).launch {
                YouTube.likeVideo(id, !liked)
                this.cancel()
            }
        }
    }

    fun toggleLibrary() = copy(
        liked = if (inLibrary == null) liked else false,
        inLibrary = if (inLibrary == null) LocalDateTime.now() else null,
        likedDate = if (inLibrary == null) likedDate else null
    )

    /**
     * True when this song was downloaded by the app itself (files live in
     * Music/NovaMusic), as opposed to a scanned on-device file.
     */
    fun isDownloadedByApp(): Boolean {
        if (!isLocal) return false
        // Rows the device scanner imported carry the scanner's id prefix; any other
        // local row was downloaded by the app itself.
        //
        // Relying on the path alone was fragile: on Android 10+ the MediaStore DATA
        // column is frequently unavailable, so localPath falls back to a content://
        // URI containing no folder name. Such a song then looked like it had never
        // been downloaded, so its menu offered no way to delete it.
        if (!id.startsWith(LOCAL_SONG_ID_PREFIX)) return true
        return localPath?.contains("NovaMusic", ignoreCase = true) == true
    }
}
