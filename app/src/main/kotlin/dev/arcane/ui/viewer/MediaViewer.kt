@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.arcane.ui.viewer

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatDuration
import dev.arcane.vault.index.FileRecord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** The frame modes, in the order the resize chip walks them. */
private val RESIZE = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Fill" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

private const val SEEK_STEP_MS = 10_000L
private const val DOUBLE_TAP_WINDOW_MS = 300L

/** What the transient card in the middle of the screen is reporting. */
private sealed interface Hud {
    data class Seeking(val targetMs: Long) : Hud
    data class Stepping(val millis: Long) : Hud
    data class Brightness(val percent: Int) : Hud
    data class Volume(val percent: Int) : Hud
    data class Speed(val value: Float) : Hud
}

/** Where the viewer is standing inside its folder, for the swipe and the skip buttons. */
data class Pager(
    val index: Int = -1,
    val count: Int = 0,
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
) {
    val canPage: Boolean get() = count > 1
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index in 0 until count - 1

    companion object {
        val None = Pager()
    }
}

/**
 * How a tap follows another tap.
 *
 * Held in a plain class rather than in composition state because the gesture loop is not a composable and the
 * answer only matters for as long as the window is open.
 */
private class TapMemory {
    var at = 0L
}

internal val LocalPager = compositionLocalOf { Pager.None }

/**
 * Video and audio, over the decrypting data source.
 *
 * The player belongs to this composable and is released with it, because a process-wide player would keep a
 * `ChunkMap` — and so a file key — alive long after the screen that needed it closed. Media3's own controller
 * is switched off: the surface is `PlayerView`'s business, the controls are ours, which is what lets the scrub
 * bar be this app's shape rather than the library's.
 *
 * The gestures borrow the layout people already have in their hands from the players they use every day: the
 * left half of the screen is brightness, the right half is volume, dragging sideways seeks, double-tapping a
 * side steps ten seconds, and everything else is hidden until a tap brings it back. None of it needs to be
 * learned twice.
 *
 * Nothing here puts plaintext on disk. Each read decrypts one 512 KiB chunk into RAM, which is why a scrub
 * costs one chunk rather than a re-fetch: position becomes `chunkIndex = position / 524288`, and the seek
 * lands as soon as that one blob is authenticated — which is also why a drag only moves a number and the seek
 * itself waits for the finger to lift.
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaViewer(
    graph: AppGraph,
    record: FileRecord,
    audioOnly: Boolean,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    chromeHidden: Boolean,
) {
    val context = LocalContext.current
    val pager = LocalPager.current

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
            .setBufferDurationsMs(
                2_500,  // minBufferMs (responsive start for 4K without requiring 100MB buffer)
                30_000, // maxBufferMs
                1_000,  // bufferForPlaybackMs
                2_000,  // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playbackSources))
            .setLoadControl(loadControl)
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                setMediaItem(MediaItem.fromUri(graph.repository.playbackUri(record.fileId)))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // A viewer that dims the screen out from under itself is a viewer that locks the vault through the idle
    // timeout, so the screen stays awake for as long as the frame is moving.
    KeepScreenAwake(active = !audioOnly)

    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var buffered by remember(player) { mutableFloatStateOf(0f) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var chromeTick by remember(player) { mutableIntStateOf(0) }
    var speedIndex by remember(player) { mutableIntStateOf(SPEEDS.indexOf(1f)) }
    var isSpeedBoosted by remember(player) { mutableStateOf(false) }
    var resizeIndex by remember(player) { mutableIntStateOf(0) }
    var locked by remember(player) { mutableStateOf(false) }
    var hud by remember(player) { mutableStateOf<Hud?>(null) }
    var tap by remember(player) { mutableStateOf<Tap?>(null) }
    var preview by remember(player) { mutableStateOf<Long?>(null) }
    var buffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var failed by remember(player) { mutableStateOf(false) }
    var surfaceWidth by remember(player) { mutableFloatStateOf(0f) }
    var surfaceHeight by remember(player) { mutableFloatStateOf(0f) }
    val taps = remember(player) { TapMemory() }

    val onSpeedBoost: (Boolean) -> Unit = { active ->
        if (active != isSpeedBoosted) {
            isSpeedBoosted = active
            player.setPlaybackSpeed(if (active) 2.0f else SPEEDS[speedIndex])
        }
    }

    // Every deliberate interaction restarts the auto-hide clock, without which a tap on an already-visible
    // deck would leave it on a timer that started before the tap.
    fun wake() {
        controlsVisible = true
        chromeTick++
    }

    // How much of the top and bottom belongs to the chrome rather than to the gesture surface, so a press on
    // the back arrow is not also read as a tap on the picture.
    val titleBandPx = with(LocalDensity.current) { 76.dp.toPx() }
    val deckBandPx = with(LocalDensity.current) { if (audioOnly) 150.dp.toPx() else 176.dp.toPx() }

    val window = remember { context.findWindow() }
    val audio = remember { context.getSystemService(AudioManager::class.java) }

    // The window and the music stream are borrowed, not owned, so both go back the way they were found rather
    // than to a default: a player that resets the phone's volume on close is a player that gets uninstalled.
    val brightnessWas = remember { window?.attributes?.screenBrightness }
    val volumeWas = remember { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) }
    DisposableEffect(Unit) {
        onDispose {
            brightnessWas?.let { value -> window?.apply { attributes = attributes.apply { screenBrightness = value } } }
            volumeWas?.let { value ->
                runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
            }
        }
    }

    Tick(
        player = player,
        onPlaying = { isPlaying = it },
        onState = { state ->
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) failed = false
        },
        onError = {
            failed = true
            buffering = false
            wake()
        },
    )

    // Playing is an activity, but it never produces a touch: without this the idle timer locks the vault
    // ninety seconds into a film and the picture disappears under the person watching it.
    LaunchedEffect(player, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            graph.session.touch()
            delay(KEEPALIVE_MS)
        }
    }

    // Position and auto-hide are two clocks, not one: a poll that owes the scrub bar a fresh number must keep
    // ticking while the chrome is hidden, and the hide must not be postponed by the poll's own cadence.
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            position = player.currentPosition.coerceAtLeast(0L)
            buffered = player.bufferedPercentage / 100f
            delay(if (player.isPlaying) POLL_MS else SLOW_POLL_MS)
        }
    }

    LaunchedEffect(isPlaying, controlsVisible, chromeTick) {
        if (!isPlaying || !controlsVisible || locked) return@LaunchedEffect
        delay(HIDE_AFTER_MS)
        controlsVisible = false
    }

    // A single tap cannot act immediately, because the second half of a double tap has to be able to cancel it:
    // waiting inside the gesture loop would block the second tap from ever being read, so the wait happens out
    // here, where a replacement tap tears the pending one down.
    LaunchedEffect(tap) {
        val current = tap ?: return@LaunchedEffect
        if (!current.following) delay(DOUBLE_TAP_WINDOW_MS)
        if (current.following) {
            val step = if (current.x < surfaceWidth / 2f) -SEEK_STEP_MS else SEEK_STEP_MS
            player.seekTo((player.currentPosition + step).coerceIn(0L, duration))
            hud = Hud.Stepping(step)
            wake()
        } else {
            controlsVisible = !controlsVisible
        }
    }

    LaunchedEffect(hud) {
        val shown = hud ?: return@LaunchedEffect
        delay(HUD_MS)
        if (hud == shown) hud = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (audioOnly) Color.Transparent else ArcanePalette.Ink0),
    ) {
        if (audioOnly) {
            // No picture means no gesture surface, so the stage itself has to be able to bring the deck back.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { if (controlsVisible) controlsVisible = false else wake() }
                    },
            ) {
                AudioStage(record = record, position = preview ?: position, duration = duration)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = false
                        setResizeMode(RESIZE[resizeIndex].second)
                    }
                },
                update = { view ->
                    view.player = player
                    view.setResizeMode(RESIZE[resizeIndex].second)
                },
            )
            // Below the chrome, and reading the same pointer stream first: a gesture that turns out to be a
            // drag consumes itself, so the control underneath never sees the press that started it.
            if (!locked) {
                Box(
                    Modifier
                        .matchParentSize()
                        .gestureSurface(
                            taps = taps,
                            width = { surfaceWidth },
                            height = { surfaceHeight },
                            onSize = { w, h ->
                                surfaceWidth = w
                                surfaceHeight = h
                            },
                            onTap = { x, following -> tap = Tap(x, following) },
                            onChrome = { x, y ->
                                // While the chrome is up its two bands own their own presses.
                                controlsVisible &&
                                    (y < titleBandPx || y > surfaceHeight - deckBandPx)
                            },
                            onScrub = { fraction ->
                                val target = (fraction * duration).toLong().coerceIn(0L, duration)
                                preview = target
                                hud = Hud.Seeking(target)
                            },
                            onScrubEnd = { fraction ->
                                player.seekTo((fraction * duration).toLong().coerceIn(0L, duration))
                                preview = null
                                hud = null
                                wake()
                            },
                            onBrightness = { fraction ->
                                window?.apply {
                                    attributes = attributes.apply { screenBrightness = fraction.coerceIn(0.02f, 1f) }
                                }
                                hud = Hud.Brightness((fraction * 100).roundToInt())
                            },
                            onVolume = { fraction ->
                                val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                                if (max > 0) {
                                    val target = (fraction * max).roundToInt().coerceIn(0, max)
                                    runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                                }
                                hud = Hud.Volume((fraction * 100).roundToInt())
                            },
                            brightnessAt = { window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.5f },
                            progressAt = {
                                if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
                            },
                            volumeAt = {
                                val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                                if (max > 0) {
                                    (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max
                                } else {
                                    0.5f
                                }
                            },
                            onSpeedBoost = onSpeedBoost,
                        ),
                )
            }
        }

        AnimatedVisibility(
            visible = isSpeedBoosted,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(ArcanePalette.Ink0.copy(alpha = 0.85f))
                    .border(1.dp, ArcanePalette.Violet.copy(alpha = 0.5f), RoundedCornerShape(ArcaneRadius.field))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
            ) {
                Icon(
                    Icons.Rounded.FastForward,
                    contentDescription = null,
                    tint = ArcanePalette.Violet,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "2X Playback",
                    style = ArcaneType.Scale.titleSmall,
                    color = ArcanePalette.TextHigh,
                )
            }
        }

        hud?.let { current ->
            HudCard(
                hud = current,
                modifier = Modifier.align(
                    when (current) {
                        is Hud.Brightness -> Alignment.CenterStart
                        is Hud.Volume -> Alignment.CenterEnd
                        else -> Alignment.Center
                    },
                ),
            )
        }

        // A read behind a seek is flash plus AES, so the wait is short but real; without this the player looks
        // frozen for exactly as long as the chunk takes to authenticate.
        if (buffering && !failed && !locked) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(34.dp),
                color = ArcanePalette.Violet,
                strokeWidth = 2.dp,
            )
        }

        if (failed) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = ArcaneSpace.flow)
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(ArcanePalette.Ink0.copy(alpha = 0.86f))
                    .padding(horizontal = ArcaneSpace.lg, vertical = ArcaneSpace.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
            ) {
                Text("Can't play this file", style = ArcaneType.Scale.titleMedium, color = ArcanePalette.TextHigh)
                Text(
                    "This phone has no decoder for its format.",
                    style = ArcaneType.Scale.bodySmall,
                    color = ArcanePalette.TextMedium,
                )
            }
        }

        // The one control that outlives a lock: an unlock affordance, alone on the surface.
        if (locked) {
            ArcaneAction(
                icon = Icons.Rounded.Lock,
                hint = "Unlock the screen",
                onClick = { locked = false },
                target = 52.dp,
                tint = ArcanePalette.TextHigh,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !chromeHidden,
            enter = fadeIn(tween(ArcaneMotion.quick)),
            exit = fadeOut(tween(ArcaneMotion.quick)),
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding(),
        ) {
            PlayerTitle(record = record, onBack = { pager.onBack() }, onInfo = onInfo, onExport = onExport)
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !chromeHidden,
            enter = fadeIn(tween(ArcaneMotion.quick)),
            exit = fadeOut(tween(ArcaneMotion.quick)),
            modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding(),
        ) {
            ControlDeck(
                position = position,
                previewMs = preview,
                duration = duration,
                buffered = buffered,
                isPlaying = isPlaying,
                compact = audioOnly,
                pager = pager,
                speedLabel = speedLabel(SPEEDS[speedIndex]),
                resizeLabel = RESIZE[resizeIndex].first,
                // A drag moves a number; only the lift asks the data source for that chunk.
                onScrub = { fraction ->
                    val target = (fraction * duration).toLong().coerceIn(0L, duration)
                    preview = target
                    hud = Hud.Seeking(target)
                    wake()
                },
                onScrubEnd = { fraction ->
                    player.seekTo((fraction * duration).toLong().coerceIn(0L, duration))
                    preview = null
                    hud = null
                    wake()
                },
                onSeekBy = { millis ->
                    player.seekTo((player.currentPosition + millis).coerceIn(0L, duration))
                    wake()
                },
                onToggle = {
                    if (player.isPlaying) player.pause() else player.play()
                    wake()
                },
                onSpeed = {
                    speedIndex = (speedIndex + 1) % SPEEDS.size
                    player.setPlaybackSpeed(SPEEDS[speedIndex])
                    hud = Hud.Speed(SPEEDS[speedIndex])
                    wake()
                },
                onResize = {
                    resizeIndex = (resizeIndex + 1) % RESIZE.size
                    wake()
                },
                onLock = {
                    locked = true
                    controlsVisible = false
                },
            )
        }
    }
}

/** A tap, with only as much history as the double-tap window needs. */
private data class Tap(val x: Float, val following: Boolean)

/**
 * Every gesture the video surface answers to, in one pass.
 *
 * It is one `awaitEachGesture` rather than a stack of detectors because the axes have to be judged against
 * each other: a finger that has travelled sideways is scrubbing and must not also fire a tap, and a finger
 * that travelled down the left half is raising the brightness rather than dragging across the picture.
 * Deciding that in a single loop is the difference between a player that reads intent and one that does both.
 *
 * `PointerEventPass.Initial` is the point: the surface gets first refusal on the stream, ahead of the buttons
 * drawn over it, and consumes only once the gesture has committed to an axis. A press that never travels far
 * enough to commit is left alone, which is what lets the controls still be tapped.
 */
private fun Modifier.gestureSurface(
    taps: TapMemory,
    width: () -> Float,
    height: () -> Float,
    onSize: (Float, Float) -> Unit,
    onTap: (x: Float, following: Boolean) -> Unit,
    onChrome: (x: Float, y: Float) -> Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    brightnessAt: () -> Float,
    volumeAt: () -> Float,
    progressAt: () -> Float,
    onSpeedBoost: (Boolean) -> Unit,
): Modifier = pointerInput(taps) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onSize(size.width.toFloat(), size.height.toFloat())
            val slop = viewConfiguration.touchSlop
            val startedOnLeft = down.position.x < size.width / 2f
            var axis: Axis? = null
            var level = 0f
            var lastScrub: Float? = null
            var scrubOrigin = 0f
            var speedBoosted = false

            val boostJob = this@coroutineScope.launch {
                delay(350L)
                if (axis == null && !onChrome(down.position.x, down.position.y)) {
                    speedBoosted = true
                    onSpeedBoost(true)
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        // The stream can drop a pointer without ever reporting it lifted; the preview still has to
                        // end, or the scrub bar stays parked where the finger was.
                        if (axis == Axis.SCRUB) lastScrub?.let(onScrubEnd)
                        break
                    }
                    val x = change.position.x
                    val y = change.position.y
                    val dx = x - down.position.x
                    val dy = y - down.position.y

                    if (axis == null) {
                        axis = when {
                            abs(dx) > slop && abs(dx) >= abs(dy) -> Axis.SCRUB
                            abs(dy) > slop -> if (startedOnLeft) Axis.BRIGHTNESS else Axis.VOLUME
                            else -> null
                        }
                        if (axis != null) {
                            boostJob.cancel()
                            // Both axes start from where they already are: a drag is a change, and a scrub that
                            // teleported to the finger's x would make touching the left edge skip the film.
                            level = when (axis) {
                                Axis.BRIGHTNESS -> brightnessAt()
                                Axis.VOLUME -> volumeAt()
                                Axis.SCRUB -> progressAt()
                            }
                            scrubOrigin = down.position.x
                        }
                    }

                    when (axis) {
                        Axis.SCRUB -> {
                            if (width() > 0f) {
                                val fraction = (level + (x - scrubOrigin) / width()).coerceIn(0f, 1f)
                                lastScrub = fraction
                                onScrub(fraction)
                            }
                            change.consume()
                        }

                        Axis.BRIGHTNESS, Axis.VOLUME -> {
                            // Up raises both, so a drag reads as lifting a slider rather than as inverted pixels.
                            val travelled = if (height() > 0f) -dy / height() else 0f
                            val target = (level + travelled).coerceIn(0f, 1f)
                            if (axis == Axis.BRIGHTNESS) onBrightness(target) else onVolume(target)
                            change.consume()
                        }

                        null -> {
                            if (speedBoosted) {
                                change.consume()
                            }
                        }
                    }

                    if (!change.pressed) {
                        boostJob.cancel()
                        if (speedBoosted) {
                            onSpeedBoost(false)
                            speedBoosted = false
                        } else if (axis == null) {
                            val now = SystemClock.elapsedRealtime()
                            // A press that began on the title strip or the deck belongs to a button, not to the
                            // surface: without this, tapping play also toggles the chrome it is standing on.
                            if (!onChrome(down.position.x, down.position.y)) {
                                onTap(down.position.x, now - taps.at < DOUBLE_TAP_WINDOW_MS)
                            }
                            taps.at = now
                        } else if (axis == Axis.SCRUB) {
                            lastScrub?.let(onScrubEnd)
                        }
                        break
                    }
                }
            } finally {
                boostJob.cancel()
                if (speedBoosted) {
                    onSpeedBoost(false)
                    speedBoosted = false
                }
            }
        }
    }
}

private enum class Axis { SCRUB, BRIGHTNESS, VOLUME }

/** The strip that stands in for the usual header while a video is playing edge to edge. */
@Composable
private fun PlayerTitle(
    record: FileRecord,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
) {
    val pager = LocalPager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ArcanePalette.Ink0.copy(alpha = 0.82f), Color.Transparent)))
            .padding(horizontal = ArcaneSpace.gutter - 6.dp, vertical = ArcaneSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ArcaneAction(Icons.AutoMirrored.Rounded.ArrowBack, "Close", onBack, target = 48.dp)
        Text(
            record.name,
            style = ArcaneType.Scale.titleMedium,
            color = ArcanePalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pager.canPage) {
            Text(
                "${pager.index + 1} / ${pager.count}",
                style = ArcaneType.Scale.labelMedium,
                color = ArcanePalette.TextMedium,
                modifier = Modifier.padding(end = ArcaneSpace.xs),
            )
        }
        ArcaneAction(Icons.Rounded.Info, "Details", onInfo, target = 48.dp)
        ArcaneAction(Icons.Rounded.Save, "Export a copy", onExport, target = 48.dp)
    }
}

@Composable
private fun HudCard(hud: Hud, modifier: Modifier = Modifier) {
    val label = when (hud) {
        is Hud.Seeking -> formatDuration(hud.targetMs)
        is Hud.Stepping -> (if (hud.millis > 0) "+" else "") + "${hud.millis / 1000}s"
        is Hud.Brightness -> "${hud.percent}%"
        is Hud.Volume -> "${hud.percent}%"
        is Hud.Speed -> speedLabel(hud.value)
    }
    val icon = when (hud) {
        is Hud.Seeking -> Icons.Rounded.FastForward
        is Hud.Stepping -> if (hud.millis > 0) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
        is Hud.Brightness -> Icons.Rounded.Brightness6
        is Hud.Volume -> Icons.AutoMirrored.Rounded.VolumeUp
        is Hud.Speed -> Icons.Rounded.Speed
    }
    Row(
        modifier = modifier
            .padding(ArcaneSpace.xl)
            .clip(RoundedCornerShape(ArcaneRadius.field))
            .background(ArcanePalette.Ink0.copy(alpha = 0.76f))
            .padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
    ) {
        Icon(icon, null, tint = ArcanePalette.TextHigh, modifier = Modifier.size(18.dp))
        Text(label, style = ArcaneType.Scale.titleMedium, color = ArcanePalette.TextHigh)
    }
}

@Composable
private fun ControlDeck(
    position: Long,
    previewMs: Long?,
    duration: Long,
    buffered: Float,
    isPlaying: Boolean,
    compact: Boolean,
    pager: Pager,
    speedLabel: String,
    resizeLabel: String,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggle: () -> Unit,
    onSpeed: () -> Unit,
    onResize: () -> Unit,
    onLock: () -> Unit,
) {
    val shown = previewMs ?: position
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, ArcanePalette.Ink0.copy(alpha = 0.85f)))
            )
            .padding(horizontal = ArcaneSpace.gutter)
            .padding(top = ArcaneSpace.lg, bottom = ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
    ) {
        Scrubber(
            fraction = if (duration <= 0) 0f else (shown.toFloat() / duration).coerceIn(0f, 1f),
            buffered = buffered,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        ) {
            TimeText(formatDuration(shown))
            Spacer(Modifier.weight(1f))
            if (pager.canPage) {
                ArcaneAction(
                    Icons.Rounded.SkipPrevious,
                    "Previous item",
                    pager.onPrevious,
                    target = 48.dp,
                    enabled = pager.hasPrevious,
                )
            }
            if (!compact) {
                ArcaneAction(Icons.Rounded.Replay10, "Back ten seconds", { onSeekBy(-SEEK_STEP_MS) }, target = 48.dp)
            }
            PlayButton(isPlaying = isPlaying, onClick = onToggle)
            if (!compact) {
                ArcaneAction(Icons.Rounded.Forward10, "Forward ten seconds", { onSeekBy(SEEK_STEP_MS) }, target = 48.dp)
            }
            if (pager.canPage) {
                ArcaneAction(
                    Icons.Rounded.SkipNext,
                    "Next item",
                    pager.onNext,
                    target = 48.dp,
                    enabled = pager.hasNext,
                )
            }
            Spacer(Modifier.weight(1f))
            TimeText(formatDuration(duration))
        }
        if (!compact) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArcaneChip(label = speedLabel, onClick = onSpeed, selected = speedLabel != "1x")
                ArcaneChip(label = resizeLabel, onClick = onResize, selected = resizeLabel != "Fit")
                Spacer(Modifier.weight(1f))
                ArcaneAction(Icons.Rounded.LockOpen, "Lock the screen", onLock, target = 48.dp)
            }
        }
    }
}

@Composable
private fun TimeText(value: String) {
    Text(value, style = ArcaneType.Scale.labelSmall, color = ArcanePalette.TextMedium)
}

/** The one control aimed at without looking, so it is a circle and bigger than everything beside it. */
@Composable
private fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
            .pointerInput(isPlaying) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * A track and its own drag handling.
 *
 * A `Slider` would give a stepped thumb and an accessibility action that fights the drag. What a scrub bar
 * actually is: a fraction of a width, with the thumb offset by that fraction. The drag keys off nothing, so
 * one `pointerInput` pass survives every frame the poller publishes — which is the difference between a
 * scrubbable video and one that restarts the gesture under your finger twenty times a second.
 */
@Composable
private fun Scrubber(fraction: Float, buffered: Float, onScrub: (Float) -> Unit, onScrubEnd: (Float) -> Unit) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics { contentDescription = "Position, ${(fraction * 100).roundToInt()} percent" }
            .pointerInput(Unit) {
                var latest = fraction
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (widthPx > 0f) {
                            latest = (offset.x / widthPx).coerceIn(0f, 1f)
                            onScrub(latest)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        if (widthPx > 0f) {
                            latest = (change.position.x / widthPx).coerceIn(0f, 1f)
                            onScrub(latest)
                        }
                    },
                    onDragEnd = { onScrubEnd(latest) },
                    onDragCancel = { onScrubEnd(latest) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ArcanePalette.Ink4),
        )
        Box(
            Modifier
                .fillMaxWidth(buffered.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ArcanePalette.Ink5),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ArcanePalette.Violet),
        )
        Box(
            Modifier
                // Placed in the layout pass, not the composition pass: the width is measured state, and an
                // offset computed from it in composition would recompose the whole bar on every poll.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val centre = (widthPx * fraction.coerceIn(0f, 1f)).roundToInt()
                    val room = (widthPx.toInt() - placeable.width).coerceAtLeast(0)
                    layout(placeable.width, placeable.height) {
                        placeable.place((centre - placeable.width / 2).coerceIn(0, room), 0)
                    }
                }
                .size(if (fraction > 0f) 13.dp else 9.dp)
                .clip(CircleShape)
                .background(ArcanePalette.Violet),
        )
    }
}

/**
 * Media3's clock is not observable from Compose, so the position is polled separately by the caller. What this
 * publishes is everything that answers to an event rather than to a tick: playing, buffering, and the failure
 * that otherwise leaves a black rectangle with no explanation.
 */
@Composable
private fun Tick(
    player: Player,
    onPlaying: (Boolean) -> Unit,
    onState: (Int) -> Unit,
    onError: (PlaybackException) -> Unit,
) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = onPlaying(playing)
            override fun onPlaybackStateChanged(state: Int) = onState(state)
            override fun onPlayerError(error: PlaybackException) = onError(error)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}

/** The screen stays lit while a frame is moving, and the flag goes back with the composable. */
@Composable
private fun KeepScreenAwake(active: Boolean) {
    val window = LocalContext.current.findWindow()
    DisposableEffect(active, window) {
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// Compose hands out a theme-wrapped context, so the activity has to be unwound before its window is reachable.
private fun Context.findWindow(): android.view.Window? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>()
        .firstOrNull()
        ?.window

/**
 * Audio has no frame to draw, so the stage borrows the vault's own visual language — the violet ring fills
 * with the position — and leaves the rest of the screen empty on purpose.
 */
@Composable
private fun AudioStage(record: FileRecord, position: Long, duration: Long) {
    val fraction = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.flow),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(216.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                ArcanePalette.Violet,
                                ArcanePalette.Violet.copy(alpha = 0.15f),
                                ArcanePalette.Ink4,
                                ArcanePalette.Ink4,
                            ),
                        )
                    ),
            )
            Box(
                Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(ArcanePalette.Ink2),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatDuration(position),
                        style = ArcaneType.Scale.titleLarge,
                        color = ArcanePalette.TextHigh,
                    )
                    Text(
                        "-${formatDuration(duration - position)}",
                        style = ArcaneType.Scale.labelSmall,
                        color = ArcanePalette.TextFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Text(
            record.name,
            style = ArcaneType.Scale.titleMedium,
            color = ArcanePalette.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ArcaneSpace.md),
        )
        Text(
            "Audio · ${formatBytes(record.plainSize)} · ${(fraction * 100).roundToInt()}%",
            style = ArcaneType.Scale.labelSmall,
            color = ArcanePalette.TextFaint,
            modifier = Modifier.padding(top = ArcaneSpace.tight),
        )
    }
}

private fun speedLabel(speed: Float): String = if (speed == 1f) "1x" else "${speed}x"

private const val POLL_MS = 250L
private const val SLOW_POLL_MS = 500L
private const val HIDE_AFTER_MS = 3_500L
private const val HUD_MS = 900L

/** Comfortably inside the 90 s idle lock, and cheap enough to run for the length of a film. */
private const val KEEPALIVE_MS = 30_000L
