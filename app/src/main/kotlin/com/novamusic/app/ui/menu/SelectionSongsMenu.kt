/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.novamusic.app.ui.menu

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.offline.Download
import com.novamusic.app.LocalDatabase
import com.novamusic.app.LocalFileDownloader
import com.novamusic.app.playback.LocalDownloadState
import com.novamusic.app.playback.LocalFileDownloader as LocalFileDownloaderImpl
import com.novamusic.app.LocalPlayerConnection
import com.novamusic.app.LocalSyncUtils
import com.novamusic.app.R
import com.novamusic.app.db.entities.PlaylistSongMap
import com.novamusic.app.db.entities.Song
import com.novamusic.app.extensions.toMediaItem
import com.novamusic.app.models.MediaMetadata
import com.novamusic.app.models.toMediaMetadata
import com.novamusic.app.playback.queues.ListQueue
import com.novamusic.app.ui.component.DefaultDialog
import com.novamusic.app.ui.component.NewAction
import com.novamusic.app.ui.component.NewActionGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@SuppressLint("MutableCollectionMutableState")
@Composable
fun SelectionSongMenu(
    songSelection: List<Song>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
    songPosition: List<PlaylistSongMap>? = emptyList(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val syncUtils = LocalSyncUtils.current
    val downloader = LocalFileDownloader.current

    val allInLibrary by remember {
        mutableStateOf(
            songSelection.all {
                it.song.inLibrary != null
            },
        )
    }

    val allLiked by remember(songSelection) {
        mutableStateOf(
            songSelection.isNotEmpty() && songSelection.all {
                it.song.liked
            },
        )
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    var downloadedIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    LaunchedEffect(Unit) {
        database.allSongs().collect { allSongs ->
            downloadedIds = allSongs.filter { it.song.isDownloadedByApp() }.map { it.id }.toSet()
        }
    }

    LaunchedEffect(songSelection, downloadedIds) {
        if (songSelection.isEmpty()) return@LaunchedEffect
        downloader.progress.collect { progressMap ->
            downloadState =
                if (songSelection.all { it.id in downloadedIds }) {
                    Download.STATE_COMPLETED
                } else if (songSelection.all {
                        progressMap[it.id]?.state == LocalDownloadState.State.QUEUED ||
                                progressMap[it.id]?.state == LocalDownloadState.State.DOWNLOADING ||
                                it.id in downloadedIds
                    }
                ) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val notAddedList by remember {
        mutableStateOf(mutableListOf<Song>())
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            songSelection.map { it.id }
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
        onAddComplete = { songCount, playlistNames ->
            val message = when {
                songCount == 1 && playlistNames.size == 1 -> context.getString(R.string.added_to_playlist, playlistNames.first())
                songCount > 1 && playlistNames.size == 1 -> context.getString(R.string.added_n_songs_to_playlist, songCount, playlistNames.first())
                songCount == 1 -> context.getString(R.string.added_to_n_playlists, playlistNames.size)
                else -> context.getString(R.string.added_n_songs_to_n_playlists, songCount, playlistNames.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            songSelection.forEach { song ->
                                downloader.deleteLocalFile(song.id)
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        userScrollEnabled = !isPortrait,
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            // Enhanced Action Grid using NewMenuComponents
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.play),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.play),
                        onClick = {
                            onDismiss()
                            playerConnection.playQueue(
                                ListQueue(
                                    title = "Selection",
                                    items = songSelection.map { it.toMediaItem() },
                                ),
                            )
                            clearAction()
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.shuffle),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.shuffle),
                        onClick = {
                            onDismiss()
                            playerConnection.playQueue(
                                ListQueue(
                                    title = "Selection",
                                    items = songSelection.shuffled().map { it.toMediaItem() },
                                ),
                            )
                            clearAction()
                        }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = {
                            showChoosePlaylistDialog = true
                        }
                    )
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.play)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Selection",
                            items = songSelection.map { it.toMediaItem() },
                        ),
                    )
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.shuffle)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Selection",
                            items = songSelection.shuffled().map { it.toMediaItem() },
                        ),
                    )
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.add_to_queue)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.addToQueue(songSelection.map { it.toMediaItem() })
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.add_to_playlist)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.playlist_add),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    showChoosePlaylistDialog = true
                }
            )
        }
        item {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(
                            if (allInLibrary) R.string.remove_from_library else R.string.add_to_library
                        )
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(
                            if (allInLibrary) R.drawable.library_add_check else R.drawable.library_add
                        ),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    if (allInLibrary) {
                        database.query {
                            songSelection.forEach { song ->
                                update(song.song.toggleLibrary())
                            }
                        }
                    } else {
                        database.transaction {
                            songSelection.forEach { song ->
                                insert(song.toMediaMetadata())
                                inLibrary(song.id, LocalDateTime.now())
                            }
                        }
                    }
                    clearAction()
                }
            )
        }
        item {
            when (downloadState) {
                Download.STATE_COMPLETED -> {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.remove_download),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.offline),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            showRemoveDownloadDialog = true
                        }
                    )
                }
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.downloading)) },
                        leadingContent = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        },
                        modifier = Modifier.clickable {
                            showRemoveDownloadDialog = true
                        }
                    )
                }
                else -> {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.action_download)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            songSelection.forEach { song ->
                                LocalFileDownloaderImpl.enqueue(
                                    context,
                                    song.id,
                                    song.song.title,
                                    song.artists.joinToString { it.name },
                                )
                            }
                        }
                    )
                }
            }
        }
        item {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(
                            if (allLiked) R.string.dislike_all else R.string.like_all
                        )
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(
                            if (allLiked) R.drawable.favorite else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    val allLiked = songSelection.all { it.song.liked }
                    onDismiss()
                    database.query {
                        songSelection.forEach { song ->
                            if ((!allLiked && !song.song.liked) || allLiked) {
                                val s = song.song.toggleLike()
                                update(s)
                                syncUtils.likeSong(s)
                            }
                        }
                    }
                }
            )
        }
        if (songPosition?.size != 0) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.delete)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        coroutineScope.launch(Dispatchers.IO) {
                            database.withTransaction {
                                var i = 0
                                songPosition?.forEach { cur ->
                                    move(cur.playlistId, cur.position - i, Int.MAX_VALUE)
                                    delete(cur.copy(position = Int.MAX_VALUE))
                                    i++
                                }
                            }
                            withContext(Dispatchers.Main) {
                                onDismiss()
                                clearAction()
                            }
                        }
                    }
                )
            }
        }
    }
}

@SuppressLint("MutableCollectionMutableState")
@Composable
fun SelectionMediaMetadataMenu(
    songSelection: List<MediaMetadata>,
    currentItems: List<Timeline.Window>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val downloader = LocalFileDownloader.current

    val allLiked by remember(songSelection) {
        mutableStateOf(songSelection.isNotEmpty() && songSelection.all { it.liked })
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val notAddedList by remember {
        mutableStateOf(mutableListOf<Song>())
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            songSelection.map {
                database.insert(it)
                it.id
            }
        },
        onDismiss = { showChoosePlaylistDialog = false },
        onAddComplete = { songCount, playlistNames ->
            val message = when {
                songCount == 1 && playlistNames.size == 1 -> context.getString(R.string.added_to_playlist, playlistNames.first())
                songCount > 1 && playlistNames.size == 1 -> context.getString(R.string.added_n_songs_to_playlist, songCount, playlistNames.first())
                songCount == 1 -> context.getString(R.string.added_to_n_playlists, playlistNames.size)
                else -> context.getString(R.string.added_n_songs_to_n_playlists, songCount, playlistNames.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    var downloadedIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    LaunchedEffect(Unit) {
        database.allSongs().collect { allSongs ->
            downloadedIds = allSongs.filter { it.song.isDownloadedByApp() }.map { it.id }.toSet()
        }
    }

    LaunchedEffect(songSelection, downloadedIds) {
        if (songSelection.isEmpty()) return@LaunchedEffect
        downloader.progress.collect { progressMap ->
            downloadState =
                if (songSelection.all { it.id in downloadedIds }) {
                    Download.STATE_COMPLETED
                } else if (songSelection.all {
                        progressMap[it.id]?.state == LocalDownloadState.State.QUEUED ||
                                progressMap[it.id]?.state == LocalDownloadState.State.DOWNLOADING ||
                                it.id in downloadedIds
                    }
                ) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            songSelection.forEach { song ->
                                downloader.deleteLocalFile(song.id)
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        if (currentItems.isNotEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.delete)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        onDismiss()
                        var i = 0
                        currentItems.forEach { cur ->
                            if (playerConnection.player.availableCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                                playerConnection.player.removeMediaItem(cur.firstPeriodIndex - i++)
                            }
                        }
                        clearAction()
                    }
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.play)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Selection",
                            items = songSelection.map { it.toMediaItem() },
                        ),
                    )
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.shuffle)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Selection",
                            items = songSelection.shuffled().map { it.toMediaItem() },
                        ),
                    )
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.add_to_queue)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    playerConnection.addToQueue(songSelection.map { it.toMediaItem() })
                    clearAction()
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.add_to_playlist)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.playlist_add),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    showChoosePlaylistDialog = true
                }
            )
        }
        item {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.like_all)
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(
                            if (allLiked) R.drawable.favorite else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    database.query {
                        if (allLiked) {
                            songSelection.forEach { song ->
                                update(song.toSongEntity().toggleLike())
                            }
                        } else {
                            songSelection.filter { !it.liked }.forEach { song ->
                                update(song.toSongEntity().toggleLike())
                            }
                        }
                    }
                }
            )
        }
        item {
            when (downloadState) {
                Download.STATE_COMPLETED -> {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.remove_download),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.offline),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            showRemoveDownloadDialog = true
                        }
                    )
                }
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.downloading)) },
                        leadingContent = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        },
                        modifier = Modifier.clickable {
                            showRemoveDownloadDialog = true
                        }
                    )
                }
                else -> {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.action_download)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable {
                            songSelection.forEach { song ->
                                LocalFileDownloaderImpl.enqueue(
                                    context,
                                    song.id,
                                    song.title,
                                    song.artists.joinToString { it.name },
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
