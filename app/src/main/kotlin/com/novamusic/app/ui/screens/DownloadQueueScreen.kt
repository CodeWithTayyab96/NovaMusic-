/*
 * OpenTune Project Original
 * Arturo254 (github.com/Arturo254)
 *
 * DownloadQueueScreen author:
 * RajnishKMehta (github.com/RajnishKMehta)
 *
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.novamusic.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.novamusic.app.R
import com.novamusic.app.playback.LocalDownloadState
import com.novamusic.app.ui.component.EmptyPlaceholder
import com.novamusic.app.viewmodels.DownloadItem
import com.novamusic.app.viewmodels.DownloadQueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    navController: NavController,
    viewModel: DownloadQueueViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.download_queue)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = { viewModel.removeAll() }) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                EmptyPlaceholder(
                    icon = R.drawable.downloading,
                    text = stringResource(R.string.no_active_downloads)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(downloads, key = { it.songId }) { item ->
                    DownloadQueueItem(
                        item = item,
                        onRemove = { viewModel.removeDownload(item.songId) }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadQueueItem(
    item: DownloadItem,
    onRemove: () -> Unit,
) {
    val progress = item.progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "DownloadProgress")
    val showProgress =
        item.state == LocalDownloadState.State.QUEUED ||
            item.state == LocalDownloadState.State.DOWNLOADING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.song?.song?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            placeholder = painterResource(R.drawable.music_note)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.artist.isNotBlank()) {
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val statusText = when (item.state) {
                LocalDownloadState.State.QUEUED -> stringResource(R.string.queued)
                LocalDownloadState.State.DOWNLOADING -> stringResource(R.string.downloading)
                LocalDownloadState.State.COMPLETED -> stringResource(R.string.completed)
                LocalDownloadState.State.FAILED -> stringResource(R.string.failed)
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (showProgress) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = stringResource(R.string.remove),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
