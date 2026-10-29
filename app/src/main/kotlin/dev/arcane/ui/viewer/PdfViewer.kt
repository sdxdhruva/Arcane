package dev.arcane.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.arcane.data.AppGraph
import dev.arcane.data.VaultRepository
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.util.formatBytes
import dev.arcane.vault.index.FileRecord
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * A document, page by page.
 *
 * `PdfRenderer` needs a seekable descriptor and a chunk stream is not one, so this is the one viewer that
 * puts plaintext on flash for the length of a preview — the bounded exception the plan states rather than
 * hides. The file is randomly named inside the app's private directory, zeroed and unlinked when this
 * composable leaves, and swept again on every lock, so a killed process cannot leave a page behind.
 *
 * Past the repository's plaintext ceiling the viewer refuses and points at export: writing half a
 * gigabyte of plaintext in order to preview half a gigabyte of document is not a trade this app makes
 * silently.
 */
@Composable
fun PdfViewer(graph: AppGraph, record: FileRecord) {
    val pages = remember(record) { MutablePageStore() }
    var stage by remember(record) { mutableStateOf<PdfStage>(PdfStage.Working) }

    LaunchedEffect(record) {
        val outcome = runCatching {
            when (val temp = graph.repository.materialize(record.fileId)) {
                is VaultRepository.TempPlain.TooBig -> PdfStage.Refused(temp.plainSize)
                is VaultRepository.TempPlain.Ready ->
                    // A renderer that refuses this file must not leave the plaintext behind as the price of
                    // trying; the sweep would find it at the next lock, which is too late to call it tidy.
                    runCatching { pages.bind(temp.file) }
                        .getOrElse { failure -> graph.repository.releaseTempSoon(temp.file); throw failure }
            }
        }
        stage = outcome.getOrElse { PdfStage.Broken(it.message ?: "This document would not open.") }
    }

    DisposableEffect(record) {
        onDispose {
            val file = pages.release()
            // The plaintext has to go even if the composition leaves without the renderer closing cleanly,
            // which is why this is an `onDispose` and not a `finally`. The delete runs on the vault scope:
            // this composable's own scope is cancelled at the same instant, so a launch here would never run.
            if (file != null) graph.repository.releaseTempSoon(file)
        }
    }

    when (val current = stage) {
        PdfStage.Working -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = ArcanePalette.Violet)
            Text(
                "Unsealing…",
                style = ArcaneType.Scale.labelSmall,
                color = ArcanePalette.TextFaint,
                modifier = Modifier.padding(top = ArcaneSpace.sm),
            )
        }

        is PdfStage.Refused -> ViewerNote(
            "A ${formatBytes(current.plainSize)} document would put that much plaintext on flash to preview. " +
                "Export it and read it somewhere else."
        )

        is PdfStage.Broken -> ViewerNote(current.reason)
        PdfStage.Opened -> DocumentPages(pages)
    }
}

private sealed interface PdfStage {
    data object Working : PdfStage
    data class Refused(val plainSize: Long) : PdfStage
    data class Broken(val reason: String) : PdfStage
    data object Opened : PdfStage
}

/**
 * One renderer, one file, and the bitmaps drawn from it.
 *
 * `PdfRenderer` is not safe to use from two threads, so every page draw goes through a single-threaded
 * dispatcher; the store exists so the composable can ask for page 30 while page 12 is still on screen without
 * either one reaching for the renderer at the same moment.
 */
private class MutablePageStore {
    private var file: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    val pageCount: Int get() = renderer?.pageCount ?: 0

    fun bind(temp: File): PdfStage {
        val opened = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = opened
        renderer = PdfRenderer(opened)
        file = temp
        return PdfStage.Opened
    }

    /** The caller destroys what comes back; the store would rather hand the file over than decide its fate. */
    fun release(): File? {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
        return file.also { file = null }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val open = renderer ?: return null
        if (index !in 0 until pageCount) return null
        // A lock can close the renderer while a page is mid-draw; a failed draw is a blank page, not a crash.
        return runCatching {
            withContext(renderDispatcher) {
                val page = open.openPage(index)
                try {
                    val height = pageHeightAt(page, widthPx)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            }
        }.getOrNull()
    }

    /**
     * The height one page would occupy at [widthPx], read without rasterising anything.
     *
     * The list reserves this before the bitmap exists, which is what stops a document from jumping under a
     * reader's thumb every time a page finishes decrypting.
     */
    suspend fun heightFor(index: Int, widthPx: Int): Int {
        val open = renderer ?: return 0
        if (index !in 0 until pageCount) return 0
        return runCatching {
            withContext(renderDispatcher) {
                val page = open.openPage(index)
                try {
                    pageHeightAt(page, widthPx)
                } finally {
                    page.close()
                }
            }
        }.getOrDefault(0)
    }

    private fun pageHeightAt(page: PdfRenderer.Page, widthPx: Int): Int =
        (page.height * (widthPx.toFloat() / page.width)).toInt().coerceAtLeast(1)

    private companion object {
        /** PDF rasterising is CPU-bound and serialised by the renderer anyway; two threads would fight. */
        val renderDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}

@Composable
private fun DocumentPages(pages: MutablePageStore) {
    val state = rememberLazyListState()
    var current by remember(pages) { mutableIntStateOf(0) }
    val drawn = remember(pages) { mutableStateMapOf<Int, Bitmap>() }
    val reservedHeights = remember(pages) { mutableStateMapOf<Int, Int>() }

    LaunchedEffect(pages, state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { current = it }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().toInt() } - with(density) { (ArcaneSpace.xs * 2).toPx().toInt() }

        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .background(ArcanePalette.Ink1)
                .padding(horizontal = ArcaneSpace.xs),
            contentPadding = PaddingValues(vertical = ArcaneSpace.xs),
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
        ) {
            items((0 until pages.pageCount).toList()) { index ->
                val reserved = reservedHeights[index] ?: 0
                LaunchedEffect(index, pages, widthPx) {
                    if (reservedHeights[index] == null) reservedHeights[index] = pages.heightFor(index, widthPx)
                    if (drawn[index] == null) {
                        val bitmap = pages.render(index, widthPx.coerceAtLeast(MIN_PAGE_WIDTH_PX))
                        // The page may scroll off and dispose while its draw is still running. A bitmap that
                        // arrives for a dead slot has nobody left to recycle it, so this recycles it here.
                        if (bitmap != null) if (isActive) drawn[index] = bitmap else bitmap.recycle()
                    }
                }
                DisposableEffect(index) {
                    onDispose { drawn.remove(index)?.recycle() }
                }
                Box(
                    modifier = if (reserved > 0) {
                        Modifier
                            .fillMaxWidth()
                            .height(reserved.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ComposeColor.White)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ComposeColor.White)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    drawn[index]?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: CircularProgressIndicator(color = ArcanePalette.VioletDim)
                }
            }
        }

        Text(
            "${current + 1} / ${pages.pageCount}",
            style = ArcaneType.Scale.labelSmall,
            color = ArcanePalette.TextHigh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ArcaneSpace.md)
                .clip(RoundedCornerShape(20.dp))
                .background(ArcanePalette.Ink3)
                .padding(horizontal = ArcaneSpace.sm, vertical = 5.dp),
        )
    }
}

private const val MIN_PAGE_WIDTH_PX = 360
