package dev.arcane.ui.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.Source
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.arcane.data.AppGraph
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.vault.index.FileRecord
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A still or animated picture, decrypted into RAM and never to disk.
 *
 * The whole file is read because `ImageDecoder` wants a complete buffer and a photograph is the one category
 * this app can afford to hold: [dev.arcane.data.VaultRepository.readAll] refuses past its RAM limit, and the
 * refusal surfaces as a caption rather than a crash — a 90 MB TIFF belongs in an export, not in a viewer.
 *
 * Zoom and pan are applied to the drawing layer, not to the bitmap, so pinching never re-decodes or resamples
 * the source; the pixels decoded once are the pixels that scale.
 */
@Composable
fun ImageViewer(graph: AppGraph, record: FileRecord) {
    var decoded by remember(record) { mutableStateOf<Decoded?>(null) }
    var failure by remember(record) { mutableStateOf<String?>(null) }

    LaunchedEffect(record) {
        decoded = null
        failure = null
        val outcome = runCatching {
            // Decoding a 50 MP photograph is tens of milliseconds of native work and a large allocation; on
            // the main thread it is a dropped-frame stutter at best and an ANR at worst.
            val bytes = graph.repository.readAll(record.fileId)
            withContext(Dispatchers.Default) { decode(bytes) }
        }
        outcome.onSuccess { decoded = it }.onFailure {
            failure = it.message ?: "This image would not decode."
        }
    }

    val pager = LocalPager.current
    var zoom by remember(record) { mutableFloatStateOf(1f) }
    var panX by remember(record) { mutableFloatStateOf(0f) }
    var panY by remember(record) { mutableFloatStateOf(0f) }
    // The drag that is currently in flight, tracked separately from the settled pan so that a picture can be
    // slid sideways to the next one and let go, rather than only snapping between two static frames.
    var swipeX by remember(record) { mutableFloatStateOf(0f) }

    val shown = decoded
    val swipeAway = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = if (pager.canPage) "${record.name}, ${pager.index + 1} of ${pager.count}" else record.name }
            .pointerInput(record, pager.canPage, swipeAway) {
                awaitEachGesture {
                    awaitFirstDown()
                    var travelledX = 0f
                    var travelledY = 0f
                    // Pinching is not a swipe, and a zoomed picture is being moved rather than turned.
                    var swiping = pager.canPage && zoom <= 1.01f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) swiping = false
                        val next = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                        val moved = event.calculatePan()
                        if (next > 1f) {
                            panX = (panX + moved.x).coerceIn(-size.width.toFloat(), size.width.toFloat())
                            panY = (panY + moved.y).coerceIn(-size.height.toFloat(), size.height.toFloat())
                        }
                        if (swiping) {
                            travelledX += moved.x
                            travelledY += moved.y
                            swipeX = travelledX
                        }
                        zoom = next
                        if (next <= 1f && !swiping) {
                            panX = 0f
                            panY = 0f
                        }
                        event.changes.forEach { if (it.pressed) it.consume() }
                    } while (event.changes.any { it.pressed })

                    if (swiping) {
                        val sideways = abs(travelledX) > swipeAway && abs(travelledX) > abs(travelledY)
                        swipeX = 0f
                        // Swiping left moves to the next one, which is the direction a person is already
                        // reading a folder in; the ends do not wrap, because wrapping hides where the folder
                        // actually stops.
                        if (sideways) {
                            if (travelledX < 0) pager.onNext() else pager.onPrevious()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failure != null -> ViewerNote(failure ?: "")
            shown == null -> CircularProgressIndicator(color = ArcanePalette.Violet)
            else -> DecodedImage(
                decoded = shown,
                description = record.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX + swipeX
                        translationY = panY
                        alpha = if (swipeX == 0f) 1f else 0.82f
                    },
            )
        }
        if (zoom > 1.01f) {
            Text(
                "${(zoom * 100).toInt()}%",
                style = ArcaneType.Scale.labelSmall,
                color = ArcanePalette.TextFaint,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ArcaneSpace.md),
            )
        }
    }
}

@Composable
private fun DecodedImage(decoded: Decoded, description: String, modifier: Modifier = Modifier) {
    val bitmap = decoded.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        // Animated GIF and WebP: ImageDecoder returns a drawable that drives itself, and the only way to keep
        // it ticking is to hand it to the one place a drawable callback already exists.
        val drawable = decoded.drawable
        AndroidView(
            modifier = modifier,
            factory = { viewContext -> ImageView(viewContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { it.setImageDrawable(drawable) },
        )
        LaunchedEffect(drawable) { (drawable as? AnimatedImageDrawable)?.start() }
        // A ticker left running after the view is gone burns frames on an image nobody is looking at.
        DisposableEffect(drawable) {
            onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
        }
    }
}

/** Where the bytes came from decides which decoder path is even possible, so the split is stated once. */
private class Decoded(val bitmap: Bitmap?, val drawable: Drawable?)

private fun decode(bytes: ByteArray): Decoded {
    val source: Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val drawable = ImageDecoder.decodeDrawable(source)
    return if (drawable is AnimatedImageDrawable) {
        Decoded(null, drawable)
    } else {
        // Re-opening the buffer is not waste: the bitmap path is what lets Compose draw it, and the drawable
        // would have been converted anyway. One of the two is kept, never both.
        Decoded(ImageDecoder.decodeBitmap(source), null)
    }
}

/** The shared "this could not be shown" line, in the one style every viewer uses for it. */
@Composable
internal fun ViewerNote(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = ArcaneSpace.flow), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = ArcaneType.Scale.bodyMedium,
            color = ArcanePalette.TextMedium,
        )
    }
}
