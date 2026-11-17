package dev.arcane.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.data.VaultEntry
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.components.ArcaneSheet
import dev.arcane.ui.components.DetailRow
import dev.arcane.ui.components.SheetAction
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.theme.arcaneBackdrop
import dev.arcane.ui.util.extensionOf
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatModified
import dev.arcane.vault.format.ChunkGeometry
import dev.arcane.vault.index.FileRecord
import kotlinx.coroutines.launch

/** What a tapped file becomes, decided from the index record and nothing else. */
enum class ViewerKind { VIDEO, AUDIO, IMAGE, TEXT, HTML, PDF, ARCHIVE, UNKNOWN }

/**
 * The whole-screen surface a file opens into.
 *
 * It is hosted by the shell rather than by the browser, and it covers everything the shell draws underneath
 * it, because a video with a header strip under it is a video with a header strip in it. Closing is the shell
 * clearing [AppGraph.view], which is also what a lock does — so there is no state where a realm has gone and a
 * plaintext page is still up.
 */
@Composable
fun VaultViewer(graph: AppGraph, record: FileRecord, onBack: () -> Unit) {
    val kind = remember(record) { kindOf(record) }
    var showInfo by remember(record) { mutableStateOf(false) }
    var showMoreSheet by remember(record) { mutableStateOf(false) }
    var showDeleteConfirm by remember(record) { mutableStateOf(false) }
    var isEditing by remember(record.fileId) { mutableStateOf(record.plainSize == 0L) }
    val listing by graph.repository.listing.collectAsStateWithLifecycle()
    val queue by graph.viewQueue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Where this file sits among the others that open the same way. A search-filtered folder hands over only
    // the files on screen, so a swipe never lands on something the person had just filtered out; the listing
    // remains the fallback for a viewer opened before a queue existed.
    val siblings = remember(listing, queue, kind) {
        val source = queue ?: listing.entries.filterIsInstance<VaultEntry.File>().map { it.record }
        source.filter { kindOf(it) == kind }.ifEmpty { listOf(record) }
    }
    val index = remember(siblings, record) {
        siblings.indexOfFirst { it.fileId.contentEquals(record.fileId) }.coerceAtLeast(0)
    }
    val pager = remember(siblings, index, onBack) {
        Pager(
            index = index,
            count = siblings.size,
            onPrevious = { if (index > 0) graph.view(siblings[index - 1]) },
            onNext = { if (index in 0 until siblings.size - 1) graph.view(siblings[index + 1]) },
            onBack = onBack,
        )
    }

    // The contract carries the mime, so it is rebuilt when a swipe changes the kind of file being exported.
    val mime = record.mimeType ?: "application/octet-stream"
    val exportContract = remember(mime) { ActivityResultContracts.CreateDocument(mime) }
    val exportTo = rememberLauncherForActivityResult(exportContract) { target: Uri? ->
        graph.endHandoff()
        if (target != null) graph.repository.export(record.fileId, target)
    }
    val exportCurrent = {
        graph.beginHandoff()
        exportTo.launch(record.name)
    }

    BackHandler {
        when {
            showDeleteConfirm -> showDeleteConfirm = false
            showMoreSheet -> showMoreSheet = false
            showInfo -> showInfo = false
            kind == ViewerKind.TEXT && isEditing && record.plainSize > 0L -> isEditing = false
            else -> onBack()
        }
    }

    // A video plays edge to edge with its own strip of controls, because a header row above a film is a
    // header row inside it. Everything else keeps the header, which is where its export button lives.
    CompositionLocalProvider(LocalPager provides pager) {
        if (kind == ViewerKind.TEXT && isEditing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .arcaneBackdrop()
                    .systemBarsPadding(),
            ) {
                TextEditor(
                    graph = graph,
                    record = record,
                    onBack = {
                        if (record.plainSize == 0L) onBack() else isEditing = false
                    },
                    onRecordUpdated = { updated ->
                        graph.view(updated)
                    },
                    onSwitchToReader = if (record.plainSize > 0L) { { isEditing = false } } else null,
                )
            }
            return@CompositionLocalProvider
        }

        if (kind == ViewerKind.VIDEO) {
            Box(Modifier.fillMaxSize().arcaneBackdrop()) {
                ViewerBody(
                    graph = graph,
                    record = record,
                    kind = kind,
                    onInfo = { showInfo = !showInfo },
                    onExport = exportCurrent,
                    chromeHidden = showInfo || showMoreSheet || showDeleteConfirm,
                    onMore = { showMoreSheet = true },
                )
                AnimatedVisibility(
                    visible = showInfo,
                    enter = fadeIn(tween(ArcaneMotion.quick)),
                    exit = fadeOut(tween(ArcaneMotion.quick)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(ArcanePalette.Scrim)
                            .pointerInput(Unit) { detectTapGestures { showInfo = false } }
                    ) {
                        InfoCard(
                            record,
                            modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding(),
                        )
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .arcaneBackdrop()
                    .systemBarsPadding(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    ViewerHeader(
                        title = record.name,
                        subtitle = subtitleFor(kind, record),
                        pager = pager,
                        onBack = onBack,
                        onInfo = { showInfo = !showInfo },
                        onExport = exportCurrent,
                        onEdit = if (kind == ViewerKind.TEXT && record.plainSize <= MAX_EDITABLE_BYTES) {
                            { isEditing = true }
                        } else null,
                        onMore = { showMoreSheet = true },
                    )
                    Box(Modifier.weight(1f)) {
                        ViewerBody(
                            graph = graph,
                            record = record,
                            kind = kind,
                            onInfo = { showInfo = !showInfo },
                            onExport = exportCurrent,
                            chromeHidden = showInfo || showMoreSheet || showDeleteConfirm,
                            onMore = { showMoreSheet = true },
                            onEdit = if (kind == ViewerKind.TEXT && record.plainSize <= MAX_EDITABLE_BYTES) {
                                { isEditing = true }
                            } else null,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showInfo,
                    enter = fadeIn(tween(ArcaneMotion.quick)),
                    exit = fadeOut(tween(ArcaneMotion.quick)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(ArcanePalette.Scrim)
                            .pointerInput(Unit) { detectTapGestures { showInfo = false } }
                    ) {
                        InfoCard(record, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }

        // 3-dot options sheet
        ArcaneSheet(
            visible = showMoreSheet,
            onDismiss = { showMoreSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcaneSpace.md)
                    .padding(bottom = ArcaneSpace.xs),
            ) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ArcanePalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatBytes(record.plainSize)} · ${labelFor(kind)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextMedium,
                )
            }

            SheetAction(
                label = "Details",
                subtitle = "View encryption and file metadata",
                icon = Icons.Rounded.Info,
                onClick = {
                    showMoreSheet = false
                    showInfo = true
                },
            )
            SheetAction(
                label = "Export a copy",
                subtitle = "Save an unencrypted copy outside the vault",
                icon = Icons.Rounded.Save,
                onClick = {
                    showMoreSheet = false
                    exportCurrent()
                },
            )
            SheetAction(
                label = "Delete ${labelFor(kind).lowercase()}",
                subtitle = "Permanently remove from encrypted vault",
                icon = Icons.Rounded.DeleteOutline,
                destructive = true,
                onClick = {
                    showMoreSheet = false
                    showDeleteConfirm = true
                },
            )
        }

        // Delete confirmation sheet
        ArcaneSheet(
            visible = showDeleteConfirm,
            onDismiss = { showDeleteConfirm = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcaneSpace.md)
                    .padding(bottom = ArcaneSpace.md),
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
            ) {
                Text(
                    text = "Delete \"${record.name}\"?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "This ${labelFor(kind).lowercase()} will be shredded and permanently deleted from the vault. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArcanePalette.TextMedium,
                )
                Row(
                    modifier = Modifier.padding(top = ArcaneSpace.xs),
                    horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
                ) {
                    ArcaneButton(
                        label = "Cancel",
                        filled = false,
                        onClick = { showDeleteConfirm = false },
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneButton(
                        label = "Delete",
                        icon = Icons.Rounded.Delete,
                        onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                try {
                                    val fileIdToDelete = record.fileId
                                    val remaining = siblings.filterNot { it.fileId.contentEquals(fileIdToDelete) }
                                    val nextSibling = if (index < remaining.size) remaining[index] else remaining.lastOrNull()

                                    graph.repository.delete(listOf(fileIdToDelete))
                                    graph.removeFilesFromQueue(listOf(fileIdToDelete))
                                    if (nextSibling != null) {
                                        graph.view(nextSibling, remaining)
                                    } else {
                                        onBack()
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The classification, in one place.
 *
 * Extension is consulted alongside mime on purpose: `.md`, `.kt` and `.srt` arrive as
 * `application/octet-stream` from most sources, and an HTML file with no mime recorded must not be handed to
 * a text renderer that would show its tags rather than render them.
 */
internal fun kindOf(record: FileRecord): ViewerKind {
    val mime = record.mimeType?.lowercase().orEmpty()
    val ext = extensionOf(record.name)
    return when {
        mime.startsWith("video/") || ext in VIDEO_EXT -> ViewerKind.VIDEO
        mime.startsWith("audio/") || ext in AUDIO_EXT -> ViewerKind.AUDIO
        mime.startsWith("image/") || ext in IMAGE_EXT -> ViewerKind.IMAGE
        mime.startsWith("text/html") || ext in HTML_EXT -> ViewerKind.HTML
        mime == "application/pdf" || ext == "pdf" -> ViewerKind.PDF
        mime.startsWith("text/") || ext in TEXT_EXT -> ViewerKind.TEXT
        ext in ARCHIVE_EXT -> ViewerKind.ARCHIVE
        else -> ViewerKind.UNKNOWN
    }
}

private fun subtitleFor(kind: ViewerKind, record: FileRecord): String =
    "${labelFor(kind)} · ${formatBytes(record.plainSize)}"

private fun labelFor(kind: ViewerKind): String = when (kind) {
    ViewerKind.VIDEO -> "Video"
    ViewerKind.AUDIO -> "Audio"
    ViewerKind.IMAGE -> "Image"
    ViewerKind.TEXT -> "Text"
    ViewerKind.HTML -> "Web page"
    ViewerKind.PDF -> "Document"
    ViewerKind.ARCHIVE -> "Archive"
    ViewerKind.UNKNOWN -> "File"
}

@Composable
private fun ViewerHeader(
    title: String,
    subtitle: String,
    pager: Pager,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ArcaneSpace.gutter - 6.dp,
                end = ArcaneSpace.gutter,
                top = ArcaneSpace.tight,
                bottom = ArcaneSpace.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ArcaneAction(Icons.AutoMirrored.Rounded.ArrowBack, "Close", onBack, target = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = ArcaneType.Scale.titleMedium,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The position is said here rather than only drawn, because "3 of 12" is the sentence that
                // tells a person a swipe goes somewhere.
                if (pager.canPage) "$subtitle · ${pager.index + 1} of ${pager.count}" else subtitle,
                style = ArcaneType.Scale.labelSmall,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pager.canPage) {
            ArcaneAction(
                Icons.Rounded.SkipPrevious,
                "Previous item",
                pager.onPrevious,
                target = 48.dp,
                enabled = pager.hasPrevious,
            )
            ArcaneAction(
                Icons.Rounded.SkipNext,
                "Next item",
                pager.onNext,
                target = 48.dp,
                enabled = pager.hasNext,
            )
        }
        if (onEdit != null) {
            ArcaneAction(
                icon = Icons.Rounded.Edit,
                hint = "Edit document",
                onClick = onEdit,
                target = 48.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        ArcaneAction(Icons.Rounded.Info, "Details", onInfo, target = 48.dp)
        ArcaneAction(Icons.Rounded.Save, "Export a copy", onExport, target = 48.dp)
        if (onMore != null) {
            ArcaneAction(Icons.Rounded.MoreVert, "More options", onMore, target = 48.dp)
        }
    }
}

@Composable
private fun ViewerBody(
    graph: AppGraph,
    record: FileRecord,
    kind: ViewerKind,
    onInfo: () -> Unit = {},
    onExport: () -> Unit = {},
    chromeHidden: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    when (kind) {
        ViewerKind.VIDEO, ViewerKind.AUDIO -> MediaViewer(
            graph = graph,
            record = record,
            audioOnly = kind == ViewerKind.AUDIO,
            onInfo = onInfo,
            onExport = onExport,
            chromeHidden = chromeHidden,
            onMore = onMore,
        )

        ViewerKind.IMAGE -> ImageViewer(graph = graph, record = record)
        ViewerKind.TEXT -> TextReader(graph = graph, record = record, monospace = false, onEdit = onEdit)
        ViewerKind.HTML -> HtmlViewer(graph = graph, record = record)
        ViewerKind.PDF -> PdfViewer(graph = graph, record = record)
        ViewerKind.ARCHIVE -> ArchiveViewer(graph = graph, record = record)
        ViewerKind.UNKNOWN -> BinaryPreview(graph = graph, record = record)
    }
}

/**
 * What the file is, told plainly — including the one sentence that matters more than the metadata: exporting
 * hands a plaintext copy to something this app does not control.
 */
@Composable
private fun InfoCard(record: FileRecord, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter)
            .padding(bottom = ArcaneSpace.md)
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink3)
            .padding(ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DetailRow("Name", record.name)
        DetailRow("Size", formatBytes(record.plainSize))
        DetailRow("Kind", record.mimeType ?: "unclassified")
        DetailRow("Added", formatModified(record.addedAtMillis))
        DetailRow("Modified", formatModified(record.modifiedAtMillis))
        DetailRow("Encrypted pieces", ChunkGeometry.chunkCountFor(record.plainSize).toString())
        Text(
            "Exporting writes one plaintext copy outside the vault, where this app's protection stops.",
            style = ArcaneType.Scale.labelSmall,
            color = ArcanePalette.TextFaint,
            modifier = Modifier.padding(top = ArcaneSpace.xs),
        )
    }
}

private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "mpg", "mpeg")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aiff")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "tiff")
private val HTML_EXT = setOf("html", "htm", "xhtml", "mhtml")
private val ARCHIVE_EXT = setOf("zip", "cbz", "epub")
private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "conf",
    "kt", "kts", "java", "py", "js", "ts", "go", "rs", "c", "h", "cpp", "hpp", "sh", "sql", "srt", "vtt",
    "ass", "ssa", "gradle", "properties", "env",
)

private const val MAX_EDITABLE_BYTES = 5L * 1024 * 1024
