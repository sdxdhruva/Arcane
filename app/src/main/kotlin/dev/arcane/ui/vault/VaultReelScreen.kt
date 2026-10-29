package dev.arcane.ui.vault

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcanePill
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatDuration
import dev.arcane.ui.viewer.ViewerKind
import dev.arcane.ui.viewer.kindOf
import dev.arcane.vault.index.FileRecord
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An Instagram/TikTok-style continuous discovery reel for encrypted vault media.
 *
 * Randomly aggregates all videos and photos across every vault folder. Swiping up/down
 * smoothly transitions from one photo/video to the next with natural snapping.
 *
 * Single-player resource policy: Only the active page instantiates and runs ExoPlayer,
 * preventing hardware decoder exhaustion. Inactive pages show lightweight RAM thumbnails.
 */
@Composable
fun VaultReelScreen(
    graph: AppGraph,
    onClose: () -> Unit,
) {
    var shuffleKey by remember { mutableIntStateOf(0) }

    val mediaItems = remember(graph.holder.current?.realm?.index, shuffleKey) {
        val files = graph.holder.current?.realm?.index?.files.orEmpty()
        files.filter { record ->
            val kind = kindOf(record)
            kind == ViewerKind.VIDEO || kind == ViewerKind.IMAGE
        }.shuffled(Random(System.currentTimeMillis() + shuffleKey * 7919L))
    }

    BackHandler(onBack = onClose)

    if (mediaItems.isEmpty()) {
        ReelEmptyState(onClose = onClose)
        return
    }

    val pagerState = rememberPagerState(pageCount = { mediaItems.size })
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }

    // Auto-dim chrome after inactivity
    LaunchedEffect(pagerState.currentPage, chromeVisible) {
        if (!chromeVisible) return@LaunchedEffect
        delay(3500L)
        chromeVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            key = { index -> mediaItems.getOrNull(index)?.fileId?.contentHashCode() ?: index },
        ) { pageIndex ->
            val record = mediaItems.getOrNull(pageIndex)
            if (record != null) {
                val isActive = pagerState.currentPage == pageIndex
                val isVideo = kindOf(record) == ViewerKind.VIDEO

                if (isVideo) {
                    ReelVideoPage(
                        graph = graph,
                        record = record,
                        isActive = isActive,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                    )
                } else {
                    ReelImagePage(
                        graph = graph,
                        record = record,
                        isActive = isActive,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                    )
                }
            }
        }

        // Top Floating HUD (Back, Counter, Shuffle)
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReelTopBar(
                onBack = onClose,
                onShuffle = {
                    shuffleKey++
                    scope.launch { pagerState.scrollToPage(0) }
                },
            )
        }
    }
}

@Composable
private fun ReelTopBar(
    onBack: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.82f),
                        Color.Black.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(horizontal = 14.dp, vertical = ArcaneSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ArcaneAction(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            hint = "Close Reels",
            onClick = onBack,
            target = 44.dp,
            tint = Color.White,
        )

        ArcaneAction(
            icon = Icons.Rounded.Shuffle,
            hint = "Reshuffle Reel stream",
            onClick = onShuffle,
            target = 44.dp,
            tint = ArcanePalette.Cyan,
        )
    }
}

/**
 * An active video page inside the vertical Reel pager.
 *
 * Gestures:
 * 1. Single tap: Play / Pause toggle with centered animated flash icon.
 * 2. Long press / Hold: 2x speed playback with top floating "⚡ 2.0x" badge.
 * 3. Bottom scrub line: Dragging horizontally across bottom 48dp skips/scrubs video in real-time.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ReelVideoPage(
    graph: AppGraph,
    record: FileRecord,
    isActive: Boolean,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current

    if (!isActive) {
        // Inactive: render thumbnail without instantiating ExoPlayer
        ReelThumbnailPlaceholder(graph = graph, record = record)
        return
    }

    KeepScreenAwake(active = true)

    val player = remember(record) {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 30_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playbackSources))
            .setLoadControl(loadControl)
            .build().apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                repeatMode = Player.REPEAT_MODE_ONE // Reel infinite loop
                setMediaItem(MediaItem.fromUri(graph.repository.playbackUri(record.fileId)))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Keep vault session unlocked while watching video
    LaunchedEffect(player) {
        while (true) {
            graph.session.touch()
            delay(15_000L)
        }
    }

    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var isSpeedBoosted by remember(player) { mutableStateOf(false) }
    var flashPlayPause by remember(player) { mutableStateOf<Boolean?>(null) }
    var isDraggingTimeline by remember(player) { mutableStateOf(false) }
    var dragPositionMs by remember(player) { mutableLongStateOf(0L) }

    // Progress update loop
    LaunchedEffect(player) {
        while (true) {
            if (!isDraggingTimeline) {
                position = player.currentPosition.coerceAtLeast(0L)
            }
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            isPlaying = player.isPlaying
            delay(100L)
        }
    }

    // Play/Pause flash dismiss
    LaunchedEffect(flashPlayPause) {
        if (flashPlayPause != null) {
            delay(550L)
            flashPlayPause = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        // ExoPlayer View
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                }
            },
            update = { view ->
                view.player = player
            },
        )

        // Gesture Overlay (Tap to Pause, Long-Press for 2x, Chrome Toggle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 54.dp) // Exclude bottom scrub band
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            if (player.isPlaying) {
                                player.pause()
                                isPlaying = false
                                flashPlayPause = false
                            } else {
                                player.play()
                                isPlaying = true
                                flashPlayPause = true
                            }
                            onToggleChrome()
                        },
                        onLongPress = {
                            // Handled in touch down / up below for instant boost
                        },
                        onDoubleTap = {
                            onToggleChrome()
                        },
                    )
                }
                .pointerInput(player) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var held = false
                        val startTime = System.currentTimeMillis()

                        // Wait to detect hold (250ms)
                        while (true) {
                            val event = awaitPointerEvent()
                            val uptime = System.currentTimeMillis() - startTime
                            if (!held && uptime > 220L && down.pressed) {
                                held = true
                                isSpeedBoosted = true
                                player.setPlaybackSpeed(2.0f)
                            }
                            if (event.changes.all { !it.pressed }) {
                                if (held) {
                                    isSpeedBoosted = false
                                    player.setPlaybackSpeed(1.0f)
                                }
                                break
                            }
                        }
                    }
                },
        )

        // Play / Pause Transient Animated Badge
        flashPlayPause?.let { playingNow ->
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, ArcanePalette.EdgeStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playingNow) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )
            }
        }

        // 2.0x Boost Indicator
        AnimatedVisibility(
            visible = isSpeedBoosted,
            enter = fadeIn(tween(120)) + scaleIn(tween(120)),
            exit = fadeOut(tween(150)) + scaleOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(ArcanePill)
                    .background(ArcanePalette.Ink1.copy(alpha = 0.9f))
                    .border(1.dp, ArcanePalette.Cyan, ArcanePill)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⚡ 2.0x Speed",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = ArcanePalette.Cyan,
                )
            }
        }

        // Bottom Details & Timeline Scrubber
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.90f),
                        ),
                    ),
                )
                .padding(bottom = 8.dp),
        ) {
            // Media Title & Metadata
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatBytes(record.plainSize)} · Video",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcanePalette.TextMedium,
                    )
                }

                // Interactive Timestamp Tooltip while scrubbing
                if (isDraggingTimeline) {
                    Text(
                        text = "${formatDuration(dragPositionMs)} / ${formatDuration(duration)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = ArcanePalette.Cyan,
                        modifier = Modifier
                            .clip(ArcanePill)
                            .background(ArcanePalette.Ink3)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Instagram-Style Slim Scrub Bar (3dp normal, 6dp on drag)
            val currentProgress = if (duration > 0) {
                val effectivePos = if (isDraggingTimeline) dragPositionMs else position
                (effectivePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f

            val barHeight by animateDpAsState(
                targetValue = if (isDraggingTimeline) 6.dp else 3.dp,
                label = "barHeight",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp) // Touch hit-target
                    .pointerInput(duration, widthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDraggingTimeline = true
                                val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                dragPositionMs = (fraction * duration).toLong()
                                player.seekTo(dragPositionMs)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val fraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                                dragPositionMs = (fraction * duration).toLong()
                                player.seekTo(dragPositionMs)
                            },
                            onDragEnd = {
                                isDraggingTimeline = false
                            },
                            onDragCancel = {
                                isDraggingTimeline = false
                            },
                        )
                    }
                    .pointerInput(duration, widthPx) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            val targetMs = (fraction * duration).toLong()
                            player.seekTo(targetMs)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Background Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .background(Color.White.copy(alpha = 0.25f)),
                )

                // Active Neon Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentProgress)
                        .height(barHeight)
                        .background(ArcanePalette.Cyan),
                )
            }
        }
    }
}

/**
 * An image page inside the vertical Reel pager with pinch-to-zoom support.
 */
@Composable
private fun ReelImagePage(
    graph: AppGraph,
    record: FileRecord,
    isActive: Boolean,
    onToggleChrome: () -> Unit,
) {
    var decoded by remember(record) { mutableStateOf<DecodedImageResult?>(null) }
    var failure by remember(record) { mutableStateOf<String?>(null) }
    var zoom by remember(record) { mutableFloatStateOf(1f) }
    var panX by remember(record) { mutableFloatStateOf(0f) }
    var panY by remember(record) { mutableFloatStateOf(0f) }

    LaunchedEffect(record, isActive) {
        if (!isActive) return@LaunchedEffect
        if (decoded != null) return@LaunchedEffect

        val outcome = runCatching {
            val bytes = graph.repository.readAll(record.fileId)
            withContext(Dispatchers.Default) { decodeReelImage(bytes) }
        }
        outcome.onSuccess { decoded = it }.onFailure {
            failure = it.message ?: "This image could not be loaded."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val result = decoded
        when {
            result?.bitmap != null -> {
                Image(
                    bitmap = result.bitmap.asImageBitmap(),
                    contentDescription = record.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        }
                        .pointerInput(record) {
                            detectTapGestures(
                                onTap = { onToggleChrome() },
                                onDoubleTap = {
                                    if (zoom > 1.05f) {
                                        zoom = 1f
                                        panX = 0f
                                        panY = 0f
                                    } else {
                                        zoom = 2.5f
                                    }
                                },
                            )
                        }
                        .pointerInput(record) {
                            detectReelImageTransform(
                                getZoom = { zoom },
                                onTransform = { zoomChange, panChange ->
                                    val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                    zoom = nextZoom
                                    if (nextZoom > 1.05f) {
                                        panX += panChange.x
                                        panY += panChange.y
                                    } else {
                                        panX = 0f
                                        panY = 0f
                                    }
                                },
                                onReset = {
                                    zoom = 1f
                                    panX = 0f
                                    panY = 0f
                                },
                            )
                        },
                )
            }
            result?.drawable != null -> {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(record) {
                            detectTapGestures(onTap = { onToggleChrome() })
                        },
                    factory = { viewContext ->
                        ImageView(viewContext).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(result.drawable)
                            (result.drawable as? AnimatedImageDrawable)?.start()
                        }
                    },
                )
            }
            failure != null -> {
                Text(
                    text = failure ?: "Could not decode photo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArcanePalette.TextMedium,
                )
            }
            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = ArcanePalette.Cyan,
                    strokeWidth = 2.dp,
                )
            }
        }

        // Bottom Details
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = record.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatBytes(record.plainSize)} · Photo",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextMedium,
            )
        }
    }
}

/**
 * Lightweight placeholder for off-screen pages.
 */
@Composable
private fun ReelThumbnailPlaceholder(graph: AppGraph, record: FileRecord) {
    var thumb by remember(record) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(record) {
        thumb = graph.thumbnails.load(record)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = ArcanePalette.Ink3,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ReelEmptyState(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(ArcaneSpace.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ArcanePalette.VioletDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = ArcanePalette.Violet,
                    modifier = Modifier.size(38.dp),
                )
            }

            Text(
                text = "No Videos or Photos",
                style = MaterialTheme.typography.titleMedium,
                color = ArcanePalette.TextHigh,
            )

            Text(
                text = "Add video or photo files to any vault folder to view them in the Reel stream.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArcanePalette.TextFaint,
            )

            Spacer(Modifier.height(ArcaneSpace.sm))

            ArcaneAction(
                icon = Icons.Rounded.Close,
                hint = "Exit Reel",
                onClick = onClose,
                target = 48.dp,
                tint = ArcanePalette.TextHigh,
            )
        }
    }
}

private class DecodedImageResult(val bitmap: Bitmap?, val drawable: Drawable?)

private fun decodeReelImage(bytes: ByteArray): DecodedImageResult {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val drawable = ImageDecoder.decodeDrawable(source)
    return if (drawable is AnimatedImageDrawable) {
        DecodedImageResult(null, drawable)
    } else {
        DecodedImageResult(ImageDecoder.decodeBitmap(source), null)
    }
}

@Composable
private fun KeepScreenAwake(active: Boolean) {
    val window = LocalContext.current.findWindow()
    DisposableEffect(active, window) {
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findWindow(): android.view.Window? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>()
        .firstOrNull()
        ?.window

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectReelImageTransform(
    getZoom: () -> Float,
    onTransform: (zoomChange: Float, panChange: androidx.compose.ui.geometry.Offset) -> Unit,
    onReset: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val downCount = event.changes.count { it.pressed }
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val currentZoom = getZoom()

            if (downCount > 1) {
                // Multi-touch: always handle pinch zoom and pan, consume touch
                onTransform(zoomChange, panChange)
                event.changes.forEach { it.consume() }
            } else if (currentZoom > 1.05f) {
                // Zoomed in: single finger pans around the zoomed image, consume touch
                onTransform(1f, panChange)
                event.changes.forEach { it.consume() }
            }
            // When downCount <= 1 and currentZoom <= 1.05f:
            // Do NOT consume! Let the vertical drag pass through to the VerticalPager!
        } while (event.changes.any { it.pressed })

        if (getZoom() <= 1.05f) {
            onReset()
        }
    }
}
