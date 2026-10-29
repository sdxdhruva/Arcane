package dev.arcane.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.Eyebrow
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.util.formatBytes
import dev.arcane.vault.index.FileRecord
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The contents of a zip, without extracting it.
 *
 * Listing needs the stream, not a seekable file, so this costs one pass over the ciphertext and writes no
 * plaintext anywhere. Extraction is deliberately absent: "extract all" is the one action that turns a single
 * encrypted file into several hundred plaintext ones, which is the exact shape of the leak this app exists to
 * prevent. The header's export hands out the archive itself, one file, to a place the user chose.
 */
@Composable
fun ArchiveViewer(graph: AppGraph, record: FileRecord) {
    var entries by remember(record) { mutableStateOf<List<ZipEntryInfo>>(emptyList()) }
    var failure by remember(record) { mutableStateOf<String?>(null) }
    var loading by remember(record) { mutableStateOf(true) }

    LaunchedEffect(record) {
        loading = true
        val outcome = runCatching { graph.repository.withStream(record.fileId) { list(it) } }
        outcome.onSuccess { entries = it }.onFailure {
            failure = it.message ?: "This archive would not open."
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
        ) {
            Eyebrow("Archive", Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text(
                    "${entries.size} items · ${formatBytes(entries.sumOf { it.size })}",
                    style = ArcaneType.Scale.labelSmall,
                    color = ArcanePalette.TextFaint,
                )
            }
        }
        when {
            failure != null -> ViewerNote(failure ?: "")
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ArcanePalette.Violet)
            }

            entries.isEmpty() -> ViewerNote("Nothing in this archive.")
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = ArcaneSpace.gutter)) {
                items(entries, key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
                    ) {
                        Icon(
                            Icons.Rounded.Archive,
                            contentDescription = null,
                            tint = ArcanePalette.TextFaint,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            entry.path,
                            style = ArcaneType.Scale.bodyMedium,
                            color = ArcanePalette.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatBytes(entry.size),
                            style = ArcaneType.Mono.copy(fontSize = 11.sp),
                            color = ArcanePalette.TextFaint,
                        )
                    }
                }
            }
        }
    }
}

private class ZipEntryInfo(val path: String, val size: Long)

/**
 * One pass, entries only.
 *
 * Sizes come from the local header where they are recorded and from zero otherwise, because a streamed zip
 * legitimately has no length until its data descriptor arrives — and guessing one would be a lie in a listing
 * whose only job is to say what is in there.
 */
private fun list(stream: InputStream): List<ZipEntryInfo> =
    ZipInputStream(BufferedInputStream(stream)).let { zip ->
        buildList {
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) add(ZipEntryInfo(entry.name, maxOf(0L, entry.size)))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

/**
 * The pane for a format this app has no renderer for: the first kilobytes, in hex and in whatever the byte
 * pattern happens to look like, and a sentence about what to do instead.
 *
 * It is not a fake preview. A person deciding whether to export a file wants its header — `%PDF`, `PK`, an
 * ID3 tag, the actual first words — and that is exactly what fits in one chunk.
 */
@Composable
fun BinaryPreview(graph: AppGraph, record: FileRecord) {
    var head by remember(record) { mutableStateOf<ByteArray?>(null) }
    var failure by remember(record) { mutableStateOf<String?>(null) }

    LaunchedEffect(record) {
        head = null
        failure = null
        val outcome = runCatching { graph.repository.bytesAt(record.fileId, 0, HEX_BYTES) }
        outcome.onSuccess { head = it }.onFailure {
            failure = it.message ?: "This file would not open."
        }
    }

    val loaded = head
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Eyebrow("No renderer for this format")
        Text(
            "It stays encrypted and scattered either way. This is its first " +
                "${formatBytes(minOf(HEX_BYTES.toLong(), record.plainSize))} bytes, so you can tell what it is.",
            style = ArcaneType.Scale.labelSmall,
            color = ArcanePalette.TextFaint,
        )
        when {
            failure != null -> ViewerNote(failure ?: "")
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ArcanePalette.Violet)
            }

            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(ArcaneRadius.card))
                    .background(ArcanePalette.Ink2)
                    .padding(ArcaneSpace.sm),
            ) {
                HexRows(loaded)
            }
        }
    }
}

@Composable
private fun HexRows(bytes: ByteArray) {
    // One monospaced block rather than a row per line: the dump is 512 lines of the same shape, and a LazyList
    // of them would measure 512 times to say nothing a single text layout could not.
    BasicText(
        text = buildHexDump(bytes),
        style = ArcaneType.Mono.copy(fontSize = 11.sp, lineHeight = 17.sp, color = ArcanePalette.TextMedium),
    )
}

private fun buildHexDump(bytes: ByteArray): String = buildString {
    var offset = 0
    while (offset < bytes.size) {
        val take = minOf(16, bytes.size - offset)
        append(offset.toString(16).padStart(8, '0'))
        append("  ")
        repeat(take) { i -> append(bytes[offset + i].toInt().and(0xFF).toString(16).padStart(2, '0')).append(' ') }
        repeat(16 - take) { append("   ") }
        append(' ')
        repeat(take) { i ->
            val value = bytes[offset + i].toInt().and(0xFF)
            append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        append('\n')
        offset += take
    }
}

private const val HEX_BYTES = 8 * 1024
