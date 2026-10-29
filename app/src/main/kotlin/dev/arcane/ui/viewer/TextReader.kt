package dev.arcane.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.util.formatBytes
import dev.arcane.vault.index.FileRecord
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Text, loaded in windows.
 *
 * The file is never read whole: each page is one [dev.arcane.data.VaultRepository.textLines] call, so a 500 MB
 * log costs the RAM of the window on screen rather than the RAM of the log, and the first frame arrives after
 * one chunk read instead of after a parse of the whole file.
 *
 * Loading continues when the bottom of what is already read comes into view — not when a timer says so, which
 * is what makes a fast flick through a long file cost exactly the windows actually looked at.
 */
@Composable
fun TextReader(graph: AppGraph, record: FileRecord, monospace: Boolean) {
    val listState = rememberLazyListState()
    var lines by remember(record) { mutableStateOf<List<String>>(emptyList()) }
    var readTo by remember(record) { mutableLongStateOf(0L) }
    var done by remember(record) { mutableStateOf(false) }
    var capped by remember(record) { mutableStateOf(false) }
    var failure by remember(record) { mutableStateOf<String?>(null) }
    var wantsMore by remember(record) { mutableStateOf(true) }
    var mono by remember(record) { mutableStateOf(monospace) }
    var wrap by remember { mutableStateOf(true) }

    LaunchedEffect(record, wantsMore) {
        if (done || failure != null || !wantsMore) return@LaunchedEffect
        wantsMore = false
        val outcome = runCatching { graph.repository.textLines(record.fileId, readTo, WINDOW_BYTES) }
        outcome.onSuccess { window ->
            if (window.lines.isNotEmpty()) lines = lines + window.lines
            readTo = window.next
            // The windows are bounded but the list they build is not, and a gigabyte log scrolled to the end
            // would otherwise hold every line it ever passed. The reader stops, and says that it stopped.
            capped = window.hasMore && readTo >= MAX_LOADED_BYTES
            done = !window.hasMore || capped
        }.onFailure {
            failure = it.message ?: "This text would not open."
        }
    }

    // The bottom-of-the-window check has to observe scrolling, not a recomposition: keyed on `lines.size`, this
    // ran once per successful load, read a layout that predated it, and then never fired again — a long file
    // stopped at the first window. `snapshotFlow` also watches the count, so a window that ends above the fold
    // pulls the next one in without the user touching anything.
    LaunchedEffect(listState) {
        snapshotFlow {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to lines.size
        }
            .distinctUntilChanged()
            .collect { (last, count) ->
                if (!done && last >= count - LOOKAHEAD_LINES) wantsMore = true
            }
    }

    Column(Modifier.fillMaxSize()) {
        ReaderToolbar(
            total = record.plainSize,
            readTo = readTo,
            mono = mono,
            wrap = wrap,
            onToggleMono = { mono = !mono },
            onToggleWrap = { wrap = !wrap },
        )
        when {
            failure != null -> ViewerNote(failure ?: "")
            lines.isEmpty() && done -> ViewerNote("No text in this file.")
            lines.isEmpty() -> ViewerNote("Opening…")
            else -> LazyColumn(state = listState, contentPadding = ReaderPadding) {
                itemsIndexed(lines) { index, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm)) {
                        Text(
                            (index + 1).toString(),
                            style = ArcaneType.Mono.copy(fontSize = 11.sp),
                            color = ArcanePalette.TextFaint,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(
                            line.ifEmpty { " " },
                            style = if (mono) ArcaneType.Mono else ArcaneType.Reader,
                            color = ArcanePalette.TextHigh,
                            softWrap = wrap,
                            maxLines = if (wrap) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!done) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = ArcaneSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
                        ) {
                            ReadingBar(fraction = readTo.toFloat() / record.plainSize.coerceAtLeast(1))
                            Text(
                                "${formatBytes(readTo)} of ${formatBytes(record.plainSize)}",
                                style = ArcaneType.Scale.labelSmall,
                                color = ArcanePalette.TextFaint,
                            )
                        }
                    }
                }
                if (capped) {
                    item {
                        Text(
                            "The reader stops after ${formatBytes(MAX_LOADED_BYTES)} so a file this size cannot " +
                                "fill the heap. Export it to read the rest.",
                            style = ArcaneType.Scale.bodySmall,
                            color = ArcanePalette.TextMedium,
                            modifier = Modifier.fillMaxWidth().padding(top = ArcaneSpace.sm),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Two real preferences rather than decoration: a log you cannot set to monospace and no-wrap is a log you
 * cannot read, and a document you cannot set to a serif face is a document nobody will read for long.
 */
@Composable
private fun ReaderToolbar(
    total: Long,
    readTo: Long,
    mono: Boolean,
    wrap: Boolean,
    onToggleMono: () -> Unit,
    onToggleWrap: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
    ) {
        ArcaneChip(label = if (mono) "Monospace" else "Proportional", onClick = onToggleMono, selected = mono)
        ArcaneChip(label = if (wrap) "Wrap" else "No wrap", onClick = onToggleWrap, selected = wrap)
        Box(Modifier.weight(1f))
        Text(
            "${formatBytes(readTo)} / ${formatBytes(total)}",
            style = ArcaneType.Scale.labelSmall,
            color = ArcanePalette.TextFaint,
        )
    }
}

@Composable
private fun ReadingBar(fraction: Float) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(ArcaneMotion.quick),
        label = "read",
    )
    Box(
        Modifier
            .width(64.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(ArcanePalette.Ink4),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ArcanePalette.VioletDim),
        )
    }
}

private val ReaderPadding = PaddingValues(
    start = ArcaneSpace.gutter,
    end = ArcaneSpace.gutter,
    top = ArcaneSpace.xs,
    bottom = ArcaneSpace.xl,
)

/** Big enough that a window is a screenful of prose, small enough that opening one is not a seek of its own. */
private const val WINDOW_BYTES = 256 * 1024

private const val LOOKAHEAD_LINES = 30

/** Where the reader stops accumulating: enough for any file a person actually reads on a phone. */
private const val MAX_LOADED_BYTES = 8L * 1024 * 1024
