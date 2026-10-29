package dev.arcane.ui.simple

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.arcane.data.AppGraph
import dev.arcane.data.DeviceAlbum
import dev.arcane.data.DeviceItem
import dev.arcane.data.MediaKind
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.ArcaneEmptyState
import dev.arcane.ui.components.Shimmer
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.arcaneCard
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatDuration
import dev.arcane.ui.util.formatModified

/**
 * Plain browsing: the phone's own files, unencrypted, exactly as MediaStore hands them over.
 *
 * This surface holds no secret, and it is the screen the app shows when a passphrase has been refused often
 * enough. Both halves of that sentence are load-bearing: it has to work like a file manager — a folder opens,
 * a photo opens, a chooser appears — and it has to contain no word or control that points at anything else on
 * this phone. Whatever is being protected here is protected by the key, not by this screen's silence.
 */
@Composable
fun SimpleBrowser(graph: AppGraph) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(graph.media.hasPermission()) }
    var album by remember { mutableStateOf<DeviceAlbum?>(null) }
    var kinds by remember { mutableStateOf(MediaKind.entries.toSet()) }
    var albums by remember { mutableStateOf<List<DeviceAlbum>>(emptyList()) }
    var items by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    // What is on screen decides when the skeleton is honest: until the query for this exact folder and filter
    // set has returned, the list beneath is either stale or about to be replaced.
    val surfaceKey = "${album?.bucketId ?: "*"}|${kinds.joinToString(",") { it.name }}"
    val loading = granted && loadedFor != surfaceKey

    var deniedCount by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        granted = results.values.all { it } && graph.media.hasPermission()
        if (!granted) deniedCount++
    }

    val request: () -> Unit = {
        if (deniedCount > 0) {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        } else {
            permissionLauncher.launch(graph.media.requiredPermissions())
        }
    }

    // Back leaves an album before it leaves this screen, which is what a person holding the phone expects a
    // file manager to do and the only reason the shell's own back handler never fires from inside a folder.
    BackHandler(enabled = album != null) { album = null }

    LaunchedEffect(granted, album, kinds, reload) {
        if (!granted) {
            albums = emptyList()
            items = emptyList()
            loadedFor = null
            return@LaunchedEffect
        }
        try {
            albums = graph.media.albums(kinds)
            items = album?.let { graph.media.items(it.bucketId, kinds) } ?: emptyList()
        } finally {
            // Even a failed read ends the skeleton; a permanent shimmer would claim work that is not happening.
            loadedFor = surfaceKey
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ArcaneSpace.gutter - 6.dp, end = ArcaneSpace.gutter, top = ArcaneSpace.sm),
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (album != null) {
                    ArcaneAction(Icons.AutoMirrored.Rounded.ArrowBack, "All folders", { album = null }, target = 48.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        album?.name ?: "Files",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ArcanePalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            !granted -> "Needs permission to read this phone"
                            loading -> if (album == null) "Reading this phone…" else "Reading this folder…"
                            album == null -> "${albums.size} folders"
                            else -> "${items.size} items · ${formatBytes(items.sumOf { it.size })}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcanePalette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (granted && album == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs)) {
                    MediaKind.entries.forEach { kind ->
                        ArcaneChip(
                            label = when (kind) {
                                MediaKind.IMAGES -> "Photos"
                                MediaKind.VIDEO -> "Video"
                                MediaKind.AUDIO -> "Audio"
                            },
                            icon = when (kind) {
                                MediaKind.IMAGES -> Icons.Rounded.PhotoLibrary
                                MediaKind.VIDEO -> Icons.Rounded.Movie
                                MediaKind.AUDIO -> Icons.Rounded.LibraryMusic
                            },
                            selected = kinds == setOf(kind),
                            onClick = {
                                kinds = if (kinds == setOf(kind)) MediaKind.entries.toSet() else setOf(kind)
                                album = null
                            },
                        )
                    }
                }
            }
        }

        when {
            !granted -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                ArcaneEmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Media permission needed",
                    body = if (deniedCount > 0) {
                        "Permission to browse photos and videos is currently denied. Enable storage access in Settings to view media."
                    } else {
                        "Only the media you allow, through the system's own list. Nothing here is copied, moved or rewritten — this screen shows what already exists."
                    },
                    actionLabel = if (deniedCount > 0) "Open Settings" else "Allow access",
                    onAction = request,
                )
            }

            album == null && loading -> AlbumSkeleton()

            album == null && albums.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                ArcaneEmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Nothing to browse yet",
                    body = "Photos, videos and music on this phone will appear here once the system's own " +
                        "library has some.",
                    actionLabel = "Look again",
                    onAction = { reload++ },
                )
            }

            album == null -> AlbumGrid(albums) { chosen -> album = chosen }

            loading -> ItemSkeleton()

            items.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                val filtered = kinds != MediaKind.entries.toSet()
                ArcaneEmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "This folder is empty",
                    body = if (filtered) {
                        "Nothing here matches the types you picked. Show every type to see the folder as it is."
                    } else {
                        "There is nothing in this album to show."
                    },
                    actionLabel = if (filtered) "Show all types" else null,
                    onAction = if (filtered) ({ kinds = MediaKind.entries.toSet() }) else null,
                )
            }

            else -> ItemGrid(items) { item -> context.openExternally(item) }
        }
    }
}

/**
 * The ordinary escape from a file list: ask the phone what else can show this.
 *
 * These are the phone's own files, already readable by anything with storage permission, so a chooser leaks
 * nothing this screen was ever hiding — and a file manager whose thumbnails go nowhere when tapped is the
 * strangest thing on it.
 */
private fun Context.openExternally(item: DeviceItem) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val choices = packageManager.queryIntentActivities(view, 0)
    if (choices.isEmpty()) return
    startActivity(Intent.createChooser(view, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun AlbumSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap)) {
                repeat(2) {
                    Shimmer(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.74f),
                        shape = RoundedCornerShape(ArcaneRadius.card),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemSkeleton() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.xs, vertical = ArcaneSpace.tight),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
    ) {
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight)) {
                repeat(3) {
                    Shimmer(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(ArcaneRadius.field),
                    )
                }
            }
        }
    }
}

/**
 * Albums, drawn as covers rather than rows: the picture is the only thing that tells one camera roll from
 * another at a glance, so it gets the square and the name sits under it.
 */
@Composable
private fun AlbumGrid(albums: List<DeviceAlbum>, onOpen: (DeviceAlbum) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 154.dp),
        contentPadding = PaddingValues(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, top = ArcaneSpace.xs, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Folders on this phone" },
    ) {
        items(albums, key = { it.bucketId }) { album ->
            AlbumCard(album) { onOpen(album) }
        }
    }
}

@Composable
private fun AlbumCard(album: DeviceAlbum, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .arcaneCard(shape = RoundedCornerShape(ArcaneRadius.card))
            .semantics {
                contentDescription = "${album.name}, ${album.itemCount} items, ${formatBytes(album.totalBytes)}"
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    Brush.linearGradient(
                        listOf(ArcanePalette.VioletDim, ArcanePalette.Ink2),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                ),
        ) {
            Icon(
                Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.42f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp),
            )
            album.cover?.let { cover ->
                AsyncImage(
                    model = cover.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${album.itemCount} items · ${formatBytes(album.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

/** Audio folders keep the row shape; everything else opens as a photo wall. */
@Composable
private fun ItemGrid(items: List<DeviceItem>, onOpen: (DeviceItem) -> Unit) {
    if (items.isNotEmpty() && items.all { it.isAudio }) {
        SoundList(items, onOpen)
    } else {
        PhotoGrid(items, onOpen)
    }
}

@Composable
private fun PhotoGrid(items: List<DeviceItem>, onOpen: (DeviceItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(horizontal = ArcaneSpace.xs, vertical = ArcaneSpace.tight),
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Items in this folder" },
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            PhotoCell(item) { onOpen(item) }
        }
    }
}

@Composable
private fun PhotoCell(item: DeviceItem, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ArcaneRadius.field))
            .background(
                Brush.linearGradient(
                    listOf(ArcanePalette.tintFor(item.mimeType, item.name), ArcanePalette.Ink2),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            .semantics { contentDescription = "${item.name}, ${formatBytes(item.size)}" }
            .clickable(onClick = onClick),
    ) {
        Icon(
            when {
                item.isVideo -> Icons.Rounded.PlayArrow
                item.isAudio -> Icons.Rounded.MusicNote
                else -> Icons.Rounded.PhotoLibrary
            },
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.40f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(26.dp),
        )
        if (item.isImage || item.isVideo) {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (item.isVideo && item.durationMillis > 0) {
            Text(
                formatDuration(item.durationMillis),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ArcanePalette.Ink0.copy(alpha = 0.62f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun SoundList(items: List<DeviceItem>, onOpen: (DeviceItem) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .arcaneCard(shape = RoundedCornerShape(ArcaneRadius.field))
                    .semantics { contentDescription = "${item.name}, ${formatDuration(item.durationMillis)}" }
                    .clickable { onOpen(item) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(ArcaneRadius.field))
                        .background(
                            Brush.linearGradient(
                                listOf(ArcanePalette.AudioTint, ArcanePalette.Ink2),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(21.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = ArcanePalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(item.size)} · ${formatModified(item.modifiedAtMillis)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcanePalette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.durationMillis > 0) {
                    Text(
                        formatDuration(item.durationMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcanePalette.TextMedium,
                    )
                }
            }
        }
    }
}
