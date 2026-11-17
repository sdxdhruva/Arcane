package dev.arcane.ui.vault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.arcane.data.backup.VaultBackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.arcane.R
import dev.arcane.data.AppGraph
import dev.arcane.data.AppPrefs
import dev.arcane.data.Transfer
import dev.arcane.data.VaultEntry
import dev.arcane.data.VaultListing
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.ArcaneEmptyState
import dev.arcane.ui.components.ArcaneSheet
import dev.arcane.ui.components.Cover
import dev.arcane.ui.components.Crumb
import dev.arcane.ui.components.DetailRow
import dev.arcane.ui.components.EntryRow
import dev.arcane.ui.components.FileTile
import dev.arcane.ui.components.FolderChoiceList
import dev.arcane.ui.components.FolderCover
import dev.arcane.ui.components.FolderTile
import dev.arcane.ui.components.OutlinedField
import dev.arcane.ui.components.PickDot
import dev.arcane.ui.components.PromptSheetContent
import dev.arcane.ui.components.SheetAction
import dev.arcane.ui.components.TileSkeleton
import dev.arcane.ui.components.TransferMeter
import dev.arcane.ui.components.glyphFor
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcanePill
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.theme.arcaneCard
import dev.arcane.ui.util.extensionOf
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatModified
import dev.arcane.ui.viewer.ViewerKind
import dev.arcane.ui.viewer.kindOf
import dev.arcane.vault.format.Hex
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.realm.PendingItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the prompt sheet is being asked to do, since one sheet serves four questions. */
private sealed interface Prompt {
    val title: String
    val fieldLabel: String
    val confirm: String

    /** Said once, under the field, where it cannot be mistaken for the label. */
    val supporting: String? get() = null

    data class NewFolder(override val title: String = "New folder") : Prompt {
        override val fieldLabel = "Folder name"
        override val confirm = "Create"
    }

    data class NewTextFile(override val title: String = "New text file") : Prompt {
        override val fieldLabel = "File name"
        override val confirm = "Create"
        override val supporting =
            "Created and encrypted directly in the vault. A .txt extension is added if omitted."
    }

    data class RenameFile(override val title: String, val initial: String) : Prompt {
        override val fieldLabel = "File name"
        override val confirm = "Rename"
    }

    data class RenameFolder(override val title: String, val initial: String) : Prompt {
        override val fieldLabel = "Folder name"
        override val confirm = "Rename"
    }

    data class Download(override val title: String = "Download into this folder") : Prompt {
        override val fieldLabel = "Link"
        override val confirm = "Download"
        override val supporting =
            "Fetched straight into the vault — nothing is written where another app could read it. " +
                "An http:// link travels unencrypted between the server and this phone; https is worth typing."
    }
}

/** How the folder is ordered. Folders stay above files in every mode, because that is what a file manager means. */
private enum class Sort(val label: String) {
    Name("Name"),
    Newest("Newest first"),
    Largest("Largest first"),
    Kind("File type"),
}

/** Layout density and structure: comfortable big grid, compact small grid, or detailed list. */
private enum class ViewMode(val label: String) {
    GRID_LARGE("Big Cards"),
    GRID_COMFORTABLE("Big Grid"),
    GRID_COMPACT("Small Grid"),
    LIST("List View"),
}

/**
 * The encrypted tree, drawn exactly like a file manager because that is the whole trick: names, folders and
 * extensions come from the index the moment the realm opens, so nothing needs a per-folder reveal step and the
 * screen is indistinguishable from one that reads a directory.
 */
@Composable
fun VaultBrowser(graph: AppGraph) {
    val listing by graph.repository.listing.collectAsStateWithLifecycle()
    val loaded by graph.repository.loaded.collectAsStateWithLifecycle()
    val transfers by graph.repository.transfers.collectAsStateWithLifecycle()
    val busy by graph.repository.busy.collectAsStateWithLifecycle()
    val prefs by graph.settings.prefs.collectAsStateWithLifecycle(initialValue = AppPrefs.EMPTY)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val freeBytes = remember(listing) {
        val usable = context.filesDir.usableSpace
        if (usable > 0L) usable else context.filesDir.freeSpace
    }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.GRID_COMFORTABLE) }
    var sort by rememberSaveable { mutableStateOf(Sort.Name) }
    var sorting by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var reels by remember { mutableStateOf(false) }
    var portalScreen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val portalStatus by graph.portal.status.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    var quiet by remember { mutableStateOf(graph.ringer.hasAccess) }
    var choices by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingItem>>(emptyList()) }

    val opened by graph.holder.opened.collectAsStateWithLifecycle()
    val viewing by graph.viewing.collectAsStateWithLifecycle()
    LaunchedEffect(opened) {
        if (opened == null) {
            reels = false
            web = false
            portalScreen = false
            selected = emptySet()
        }
    }

    // Back unwinds this screen before it unwinds the app: the sheet, then the selection, then the search,
    // then the folder. Without this, back inside a subfolder finishes the activity and looks like a crash.
    BackHandler(
        enabled = viewing == null && !reels && !web && !portalScreen &&
            (confirmingDelete || menu || moving || sorting || prompt != null || searching || selected.isNotEmpty() || listing.folderId != null),
    ) {
        when {
            confirmingDelete -> confirmingDelete = false
            menu -> menu = false
            moving -> moving = false
            sorting -> sorting = false
            prompt != null -> prompt = null
            searching -> {
                searching = false
                query = ""
                searchAll = false
            }
            selected.isNotEmpty() -> selected = emptySet()
            else -> scope.launch { graph.repository.show(listing.parentToGo()) }
        }
    }

    // Re-read as the sheet opens rather than remembering forever: the grant lives on a system page the owner
    // can visit from anywhere, and a row that promises silence it no longer has the right to give is worse
    // than one that asks for it.
    LaunchedEffect(menu) { if (menu) quiet = graph.ringer.hasAccess }

    // The pick is the app asking the phone for a file, not the user leaving the app, so it is surrounded by a
    // handoff: without one the instant background lock wipes the realm mid-picker and the return lands on the
    // gate with a finished pick nobody can import.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        graph.endHandoff()
        if (uris.isNotEmpty()) {
            graph.repository.import(uris, listing.folderId)
        }
    }
    val resumePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        graph.endHandoff()
        val item = pending.firstOrNull()
        if (uri != null && item != null) graph.repository.resumePending(item, uri)
    }

    var exportTargetRecord by remember { mutableStateOf<FileRecord?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target: Uri? ->
        graph.endHandoff()
        exportTargetRecord?.let { record ->
            if (target != null) graph.repository.export(record.fileId, target)
        }
        exportTargetRecord = null
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        graph.endHandoff()
        if (uri != null) {
            scope.launch {
                val res = graph.backup.exportBackup(uri)
                when (res) {
                    is VaultBackupManager.BackupResult.Success -> {
                        android.widget.Toast.makeText(
                            context,
                            "Encrypted backup saved (${formatBytes(res.totalBytes)}, ${res.fileCount} items)",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    is VaultBackupManager.BackupResult.Error -> {
                        android.widget.Toast.makeText(context, res.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        graph.endHandoff()
        if (uri != null) {
            scope.launch {
                val res = graph.backup.restoreBackup(uri)
                when (res) {
                    is VaultBackupManager.RestoreResult.Success -> {
                        graph.repository.reconcile()
                        android.widget.Toast.makeText(
                            context,
                            "Vault restored successfully from backup (${res.fileCount} items).",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    is VaultBackupManager.RestoreResult.Error -> {
                        android.widget.Toast.makeText(context, res.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { pending = graph.repository.pending() }
    LaunchedEffect(listing.folderId) { selected = emptySet() }

    val shown = remember(listing, query, sort, searchAll) {
        if (searchAll && query.isNotBlank()) {
            val allFiles = graph.holder.current?.realm?.index?.files.orEmpty().map { VaultEntry.File(it.name, it) }
            val allFolders = graph.holder.current?.realm?.index?.folders.orEmpty().map {
                val count = graph.holder.current?.realm?.index?.childFiles(it.id)?.size ?: 0
                VaultEntry.Folder(it.name, it, count)
            }
            (allFolders + allFiles).matching(query)
        } else {
            listing.arranged(sort).matching(query)
        }
    }

    val allSelected = remember(shown, selected) {
        shown.isNotEmpty() && shown.all { keyOf(it) in selected }
    }

    val toggleSelectAll = {
        selected = if (allSelected) {
            emptySet()
        } else {
            shown.map { keyOf(it) }.toSet()
        }
    }
    val picked = remember(shown, selected) { shown.filter { keyOf(it) in selected } }

    val handleOpen: (VaultEntry) -> Unit = { entry ->
        if (selected.isNotEmpty()) {
            toggle(entry, selected) { selected = it }
        } else {
            openEntry(entry, shown, scope, graph)
        }
    }
    val handlePick: (VaultEntry) -> Unit = { entry ->
        toggle(entry, selected) { selected = it }
    }

    // The page browser replaces the folder view outright rather than sliding over it: it is a different
    // surface with its own back stack, and leaving it early also disposes the WebView and its storage.
    if (web) {
        VaultWeb(
            graph = graph,
            folderId = listing.folderId,
            folderName = listing.trail.lastOrNull()?.name ?: "this folder",
            onClose = { web = false },
        )
        return
    }

    if (reels) {
        VaultReelScreen(
            graph = graph,
            onClose = { reels = false },
        )
        return
    }

    if (portalScreen) {
        VaultPortalScreen(
            graph = graph,
            onClose = { portalScreen = false },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrowserHeader(
                listing = listing,
                count = shown.size,
                bytes = listing.totalBytes,
                globalBytes = listing.globalBytes,
                freeBytes = freeBytes,
                busy = busy,
                searching = searching,
                query = query,
                searchAll = searchAll,
                onSearchAllToggle = { searchAll = it },
                onBack = { scope.launch { graph.repository.show(listing.parentToGo()) } },
                onQueryChange = { query = it },
                onSearchToggle = {
                    searching = !searching
                    if (!searching) {
                        query = ""
                        searchAll = false
                    }
                },
                onReels = { reels = true },
                onAddFiles = { graph.beginHandoff(); importPicker.launch(arrayOf("*/*")) },
                onNewFolder = { prompt = Prompt.NewFolder() },
                onMenu = { menu = true },
            )

            if (listing.trail.isNotEmpty()) {
                CrumbTrail(listing) { folderId -> scope.launch { graph.repository.show(folderId) } }
            }

            AnimatedVisibility(
                visible = portalStatus.isRunning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                PortalActiveBanner(
                    status = portalStatus,
                    onClick = { portalScreen = true },
                )
            }

            if (transfers.isNotEmpty()) {
                TransferStrip(transfers)
            }

            if (pending.isNotEmpty()) {
                UnfinishedStrip(
                    pending = pending,
                    onResume = { graph.beginHandoff(); resumePicker.launch(arrayOf("*/*")) },
                    onDiscard = { item ->
                    scope.launch {
                        graph.repository.discardPending(item)
                        pending = graph.repository.pending()
                    }
                })
            }

            if (loaded && listing.entries.isNotEmpty()) {
                ViewOptionsBar(
                    sort = sort,
                    viewMode = viewMode,
                    onOpenSort = { sorting = true },
                    onViewModeChange = { viewMode = it },
                )
            }

            when {
                // The realm is adopted before its index is read, so the honest first frame is a skeleton, not
                // "nothing in here yet" over a folder that has been full for months.
                !loaded -> VaultSkeleton()

                shown.isEmpty() && listing.entries.isEmpty() -> EmptyFolder(
                    isRoot = listing.folderId == null,
                    onAddFiles = { graph.beginHandoff(); importPicker.launch(arrayOf("*/*")) },
                    onNewFolder = { prompt = Prompt.NewFolder() },
                    onNewTextFile = { prompt = Prompt.NewTextFile() },
                )

                shown.isEmpty() -> NoMatches(onClear = { query = "" })

                viewMode == ViewMode.GRID_LARGE -> BigTileFeed(
                    entries = shown,
                    selected = selected,
                    graph = graph,
                    onOpen = handleOpen,
                    onPick = handlePick,
                )

                viewMode == ViewMode.LIST -> EntryList(
                    entries = shown,
                    selected = selected,
                    graph = graph,
                    onOpen = handleOpen,
                    onPick = handlePick,
                )

                else -> TileGrid(
                    entries = shown,
                    selected = selected,
                    graph = graph,
                    compact = viewMode == ViewMode.GRID_COMPACT,
                    onOpen = handleOpen,
                    onPick = handlePick,
                )
            }
        }

        SelectionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            count = picked.size,
            totalCount = shown.size,
            allSelected = allSelected,
            onToggleSelectAll = toggleSelectAll,
            visible = picked.isNotEmpty(),
            canExport = picked.filterIsInstance<VaultEntry.File>().size == 1,
            onClear = { selected = emptySet() },
            onRename = {
                picked.singleOrNull()?.let { entry ->
                    prompt = when (entry) {
                        is VaultEntry.File -> Prompt.RenameFile("Rename ${entry.name}", entry.name)
                        is VaultEntry.Folder -> Prompt.RenameFolder("Rename ${entry.name}", entry.name)
                    }
                }
            },
            onMove = {
                scope.launch {
                    val folderIds = picked.filterIsInstance<VaultEntry.Folder>().map { it.record.id }.toSet()
                    choices = graph.repository.folderChoices(excludeSubtreesOf = folderIds)
                    moving = true
                }
            },
            onExport = {
                val file = picked.filterIsInstance<VaultEntry.File>().singleOrNull()
                if (file != null) {
                    exportTargetRecord = file.record
                    graph.beginHandoff()
                    exportPicker.launch(file.name)
                }
            },
            onDelete = {
                confirmingDelete = true
            },
        )

        ArcaneSheet(visible = confirmingDelete, onDismiss = { confirmingDelete = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcaneSpace.md)
                    .padding(bottom = ArcaneSpace.md),
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
            ) {
                Text(
                    text = if (picked.size == 1) "Delete \"${picked.first().name}\"?" else "Delete ${picked.size} items?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "These files will be shredded and permanently overwritten with random bytes. This cannot be undone.",
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
                        onClick = { confirmingDelete = false },
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneButton(
                        label = "Delete",
                        icon = Icons.Rounded.Delete,
                        onClick = {
                            confirmingDelete = false
                            val toDeleteFiles = picked.filterIsInstance<VaultEntry.File>().map { it.record.fileId }
                            val toDeleteFolders = picked.filterIsInstance<VaultEntry.Folder>().map { it.record.id }
                            selected = emptySet()
                            scope.launch {
                                try {
                                    graph.repository.deleteEntries(toDeleteFiles, toDeleteFolders)
                                } catch (_: Throwable) {
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Sheets ride above the browser rather than replacing it, so the folder a person was standing in is
        // still behind the dialog and the screen does not reorder when the sheet closes.
        ArcaneSheet(visible = prompt != null, onDismiss = { prompt = null }) {
            prompt?.let { current ->
                PromptSheetContent(
                    title = current.title,
                    label = current.fieldLabel,
                    initial = when (current) {
                        is Prompt.NewFolder -> ""
                        is Prompt.NewTextFile -> "notes.txt"
                        is Prompt.Download -> ""
                        is Prompt.RenameFile -> current.initial
                        is Prompt.RenameFolder -> current.initial
                    },
                    confirmLabel = current.confirm,
                    supporting = current.supporting,
                    onDismiss = { prompt = null },
                    onConfirm = { value ->
                        prompt = null
                        scope.launch { applyPrompt(current, value, listing, picked, graph) }
                    },
                )
            }
        }

        ArcaneSheet(visible = menu, onDismiss = { menu = false }) {
            Text(
                listing.trail.lastOrNull()?.name ?: "The Vault",
                style = MaterialTheme.typography.titleLarge,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs),
            )
            SheetAction(
                label = when (viewMode) {
                    ViewMode.GRID_LARGE -> "Layout: Big Cards"
                    ViewMode.GRID_COMFORTABLE -> "Layout: Big Grid"
                    ViewMode.GRID_COMPACT -> "Layout: Small Grid"
                    ViewMode.LIST -> "Layout: List View"
                },
                subtitle = "Switch between big cards, grid modes, and list view",
                icon = when (viewMode) {
                    ViewMode.GRID_LARGE -> Icons.Rounded.ViewAgenda
                    ViewMode.GRID_COMFORTABLE -> Icons.Rounded.GridView
                    ViewMode.GRID_COMPACT -> Icons.Rounded.Dashboard
                    ViewMode.LIST -> Icons.AutoMirrored.Rounded.ViewList
                },
                cover = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ) {
                menu = false
                viewMode = when (viewMode) {
                    ViewMode.GRID_LARGE -> ViewMode.GRID_COMFORTABLE
                    ViewMode.GRID_COMFORTABLE -> ViewMode.GRID_COMPACT
                    ViewMode.GRID_COMPACT -> ViewMode.LIST
                    ViewMode.LIST -> ViewMode.GRID_LARGE
                }
            }
            SheetAction(
                label = "AirVault PC Portal",
                subtitle = if (portalStatus.isRunning) "Online on ${portalStatus.url} · PIN: ${portalStatus.pin}" else "Stream, browse & upload from PC browser over local Wi-Fi",
                icon = Icons.Rounded.Laptop,
                cover = if (portalStatus.isRunning) ArcanePalette.Cyan.copy(alpha = 0.35f) else ArcanePalette.Cyan.copy(alpha = 0.15f),
            ) {
                menu = false
                portalScreen = true
            }
            SheetAction(
                label = if (allSelected) "Deselect All" else "Select All",
                subtitle = if (allSelected) "Deselect all ${shown.size} items" else "Select all ${shown.size} items in this view",
                icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                cover = ArcanePalette.TextTint,
            ) {
                menu = false
                toggleSelectAll()
            }
            SheetAction(
                label = "New Text File",
                subtitle = "Create and edit a secure text file in this folder",
                icon = Icons.Rounded.EditNote,
                cover = ArcanePalette.DocTint,
            ) {
                menu = false
                prompt = Prompt.NewTextFile()
            }
            SheetAction(
                label = "New Folder",
                subtitle = "Create an encrypted subfolder in this directory",
                icon = Icons.Rounded.CreateNewFolder,
                cover = ArcanePalette.Violet.copy(alpha = 0.2f),
            ) {
                menu = false
                prompt = Prompt.NewFolder()
            }
            SheetAction(
                label = "Export Encrypted Backup",
                subtitle = "Save a zero-leak .arcane archive to external storage",
                icon = Icons.Rounded.Archive,
                cover = ArcanePalette.Violet.copy(alpha = 0.2f),
            ) {
                menu = false
                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                graph.beginHandoff()
                exportBackupLauncher.launch("Arcane_Backup_$dateStr.arcane")
            }
            SheetAction(
                label = "Restore from Backup",
                subtitle = "Import and merge files from an .arcane backup archive",
                icon = Icons.Rounded.Unarchive,
                cover = ArcanePalette.Violet.copy(alpha = 0.2f),
            ) {
                menu = false
                graph.beginHandoff()
                restoreBackupLauncher.launch(arrayOf("*/*"))
            }
            SheetAction(
                label = "Download from Link",
                subtitle = "Fetch a URL and encrypt it directly into this folder",
                icon = Icons.Rounded.CloudDownload,
                cover = ArcanePalette.DocTint,
            ) {
                menu = false
                prompt = Prompt.Download()
            }
            SheetAction(
                label = "Private Web Browser",
                subtitle = "Encrypted in-app browser with zero local traces",
                icon = Icons.Rounded.Public,
                cover = ArcanePalette.Cyan.copy(alpha = 0.2f),
            ) {
                menu = false
                web = true
            }
            SheetAction(
                label = "Media Reel (Photos & Videos)",
                subtitle = "Random discovery feed of all vault media",
                icon = Icons.Rounded.PlayCircle,
                cover = ArcanePalette.Cyan.copy(alpha = 0.2f),
            ) {
                menu = false
                reels = true
            }
            SheetAction(
                label = "Silence Phone While Open",
                subtitle = when {
                    !prefs.silentMode -> "Currently disabled"
                    quiet -> "Active — ringer silenced while vault is open"
                    else -> "Tap to grant Do-Not-Disturb access"
                },
                icon = if (prefs.silentMode && quiet) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                cover = ArcanePalette.AudioTint,
            ) {
                menu = false
                scope.launch {
                    val granted = graph.setQuietMode(!prefs.silentMode)
                    if (!granted && !prefs.silentMode) {
                        runCatching { context.startActivity(graph.ringer.accessSettings()) }
                    }
                }
            }
            SheetAction(
                label = "Lock Vault & Close",
                subtitle = "Evaporate encryption keys and lock immediately",
                icon = Icons.Rounded.Lock,
                destructive = true,
            ) {
                menu = false
                graph.lockNow()
            }
            Spacer(Modifier.height(ArcaneSpace.md))
        }

        ArcaneSheet(visible = sorting, onDismiss = { sorting = false }) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleLarge,
                color = ArcanePalette.TextHigh,
                modifier = Modifier.padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs),
            )
            Sort.entries.forEach { option ->
                SheetAction(
                    label = option.label,
                    cover = if (option == sort) MaterialTheme.colorScheme.primaryContainer else ArcanePalette.TextTint,
                    checked = option == sort,
                ) {
                    sorting = false
                    sort = option
                }
            }
            Spacer(Modifier.height(ArcaneSpace.md))
        }

        ArcaneSheet(visible = moving, onDismiss = { moving = false }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (picked.size == 1) "Move item to" else "Move ${picked.size} items to",
                    style = MaterialTheme.typography.titleLarge,
                    color = ArcanePalette.TextHigh,
                )
                ArcaneButton(
                    label = "New Folder",
                    icon = Icons.Rounded.CreateNewFolder,
                    filled = false,
                    onClick = {
                        moving = false
                        prompt = Prompt.NewFolder()
                    },
                )
            }
            FolderChoiceList(
                choices = choices,
                current = listing.folderId,
                onPick = { target ->
                    moving = false
                    scope.launch {
                        picked.filterIsInstance<VaultEntry.File>().forEach {
                            graph.repository.moveFile(it.record.fileId, target)
                        }
                        picked.filterIsInstance<VaultEntry.Folder>().forEach {
                            graph.repository.moveFolder(it.record.id, target)
                        }
                        selected = emptySet()
                    }
                },
            )
        }

    }
}

private suspend fun applyPrompt(prompt: Prompt, value: String, listing: VaultListing, picked: List<VaultEntry>, graph: AppGraph) {
    when (prompt) {
        is Prompt.NewFolder -> graph.repository.createFolder(value, listing.folderId)
        is Prompt.NewTextFile -> {
            val record = graph.repository.createTextFile(
                name = value.ifBlank { "notes.txt" },
                content = "",
                folderId = listing.folderId,
            )
            if (record != null) {
                graph.view(record)
            }
        }
        is Prompt.Download -> graph.repository.download(value, listing.folderId)
        is Prompt.RenameFile -> picked.filterIsInstance<VaultEntry.File>().singleOrNull()?.let {
            graph.repository.renameFile(it.record.fileId, value)
        }
        is Prompt.RenameFolder -> picked.filterIsInstance<VaultEntry.Folder>().singleOrNull()?.let {
            graph.repository.renameFolder(it.record.id, value)
        }
    }
}

@Composable
private fun BrowserHeader(
    listing: VaultListing,
    count: Int,
    bytes: Long,
    globalBytes: Long,
    freeBytes: Long,
    busy: Boolean,
    searching: Boolean,
    query: String,
    searchAll: Boolean,
    onSearchAllToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onReels: () -> Unit,
    onAddFiles: () -> Unit,
    onNewFolder: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ArcaneSpace.gutter - 6.dp, end = ArcaneSpace.gutter, top = ArcaneSpace.sm),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (listing.folderId != null) {
                ArcaneAction(Icons.AutoMirrored.Rounded.ArrowBack, "Up one folder", onBack, target = 48.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    listing.trail.lastOrNull()?.name ?: "The Vault",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ArcanePalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        busy -> "Reading…"
                        listing.folderId == null -> "$count items · ${formatBytes(globalBytes)} used · ${formatBytes(freeBytes)} free"
                        bytes > 0L -> "$count items (${formatBytes(bytes)}) · ${formatBytes(globalBytes)} used · ${formatBytes(freeBytes)} free"
                        else -> "$count items · ${formatBytes(globalBytes)} used · ${formatBytes(freeBytes)} free"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextFaint,
                )
            }
            ArcaneAction(
                if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                if (searching) "Close search" else "Search this folder",
                onSearchToggle,
                target = 48.dp,
            )
            ArcaneAction(
                icon = Icons.Rounded.PlayCircle,
                hint = "Random media reel",
                onClick = onReels,
                target = 48.dp,
                tint = ArcanePalette.Cyan,
            )
            ArcaneAction(
                Icons.Rounded.CreateNewFolder,
                "New folder",
                onNewFolder,
                target = 48.dp,
            )
            ArcaneAction(
                Icons.Rounded.Add,
                "Add files from the phone",
                onAddFiles,
                target = 48.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
            ArcaneAction(Icons.Rounded.MoreVert, "More actions", onMenu, target = 48.dp)
        }
        if (searching) {
            OutlinedField(
                value = query,
                onChange = onQueryChange,
                label = if (searchAll) "Search all folders" else "Filter ${listing.trail.lastOrNull()?.name ?: "The Vault"}",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
            ) {
                ArcaneChip(
                    label = if (listing.folderId == null) "The Vault" else "This folder",
                    selected = !searchAll,
                    onClick = { onSearchAllToggle(false) },
                )
                ArcaneChip(
                    label = "All folders",
                    selected = searchAll,
                    onClick = { onSearchAllToggle(true) },
                )
            }
        }
    }
}

@Composable
private fun CrumbTrail(listing: VaultListing, onGo: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.tight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("The Vault", current = listing.trail.isEmpty()) { onGo(null) }
        listing.trail.forEachIndexed { index, folder ->
            Crumb(folder.name, current = index == listing.trail.lastIndex) { onGo(folder.id) }
        }
    }
}

@Composable
private fun ViewOptionsBar(
    sort: Sort,
    viewMode: ViewMode,
    onOpenSort: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArcaneAction(
                icon = Icons.Rounded.ViewAgenda,
                hint = "Big cards",
                onClick = { onViewModeChange(ViewMode.GRID_LARGE) },
                tint = if (viewMode == ViewMode.GRID_LARGE) MaterialTheme.colorScheme.primary else ArcanePalette.TextFaint,
                target = 38.dp,
            )
            ArcaneAction(
                icon = Icons.Rounded.GridView,
                hint = "Big grid",
                onClick = { onViewModeChange(ViewMode.GRID_COMFORTABLE) },
                tint = if (viewMode == ViewMode.GRID_COMFORTABLE) MaterialTheme.colorScheme.primary else ArcanePalette.TextFaint,
                target = 38.dp,
            )
            ArcaneAction(
                icon = Icons.Rounded.Dashboard,
                hint = "Small grid",
                onClick = { onViewModeChange(ViewMode.GRID_COMPACT) },
                tint = if (viewMode == ViewMode.GRID_COMPACT) MaterialTheme.colorScheme.primary else ArcanePalette.TextFaint,
                target = 38.dp,
            )
            ArcaneAction(
                icon = Icons.AutoMirrored.Rounded.ViewList,
                hint = "List view",
                onClick = { onViewModeChange(ViewMode.LIST) },
                tint = if (viewMode == ViewMode.LIST) MaterialTheme.colorScheme.primary else ArcanePalette.TextFaint,
                target = 38.dp,
            )
        }
        ArcaneChip(
            label = "Sort · ${sort.label}",
            icon = Icons.AutoMirrored.Rounded.Sort,
            onClick = onOpenSort,
        )
    }
}

@Composable
private fun TransferStrip(transfers: List<Transfer>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        transfers.take(3).forEach { transfer ->
            TransferMeter(
                fraction = transfer.fraction,
                label = transfer.name,
                detail = "${formatBytes(transfer.doneBytes)} / ${formatBytes(transfer.totalBytes)}",
                failed = transfer.failure != null,
            )
        }
        if (transfers.size > 3) {
            Text(
                "${transfers.size - 3} more in this queue",
                style = MaterialTheme.typography.labelMedium,
                color = ArcanePalette.TextFaint,
            )
        }
    }
}

@Composable
private fun UnfinishedStrip(pending: List<PendingItem>, onResume: () -> Unit, onDiscard: (PendingItem) -> Unit) {
    val first = pending.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
    ) {
        TransferMeter(
            fraction = if (first.plainSize <= 0) 0f else first.chunksDone.toFloat() / first.chunkCount.toFloat(),
            label = "Unfinished: ${first.name}",
            detail = "${formatBytes(first.chunksDone * CHUNK)} of ${formatBytes(first.plainSize)}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs)) {
            ArcaneChip(label = if (pending.size > 1) "Finish ${pending.size} imports" else "Finish this import", onClick = onResume)
            ArcaneChip(label = "Discard", icon = Icons.Rounded.Delete, onClick = { onDiscard(first) })
        }
    }
}

@Composable
private fun PortalActiveBanner(
    status: dev.arcane.portal.AirVaultServer.PortalStatus,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = ArcanePalette.Ink3,
        shape = RoundedCornerShape(ArcaneRadius.field),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(ArcanePalette.Mint, CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "AirVault Portal Active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "PIN ${status.pin}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    status.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.Laptop,
                contentDescription = "Open AirVault Portal",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TileGrid(
    entries: List<VaultEntry>,
    selected: Set<String>,
    graph: AppGraph,
    compact: Boolean,
    onOpen: (VaultEntry) -> Unit,
    onPick: (VaultEntry) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val isScrolling = gridState.isScrollInProgress

    val candidateFocusedKey by remember(entries) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null
            val vCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val videoKeys = entries.filter { it is VaultEntry.File && kindOf(it.record) == ViewerKind.VIDEO }
                .map { keyOf(it) }
                .toSet()
            if (videoKeys.isEmpty()) return@derivedStateOf null

            visibleItems.filter { it.key in videoKeys }
                .minByOrNull { item ->
                    val itemCenter = item.offset.y + (item.size.height / 2)
                    kotlin.math.abs(itemCenter - vCenter)
                }?.key as? String
        }
    }

    var activePreviewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidateFocusedKey, isScrolling) {
        if (isScrolling) {
            activePreviewKey = null
        } else {
            delay(160L) // Wait for fling/scroll to settle smoothly
            activePreviewKey = candidateFocusedKey
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (compact) 104.dp else 152.dp),
        contentPadding = PaddingValues(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, top = ArcaneSpace.xs, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else ArcaneSpace.tileGap),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else ArcaneSpace.tileGap),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Vault contents" },
    ) {
        items(entries, key = { keyOf(it) }) { entry ->
            val isPicked = keyOf(entry) in selected
            val isActive = keyOf(entry) == activePreviewKey
            when (entry) {
                is VaultEntry.Folder -> FolderTile(
                    name = entry.name,
                    caption = entry.caption(),
                    picked = isPicked,
                    compact = compact,
                    onClick = { onOpen(entry) },
                    onLongClick = { onPick(entry) },
                )

                is VaultEntry.File -> {
                    val isVid = kindOf(entry.record) == ViewerKind.VIDEO
                    FileTile(
                        name = entry.name,
                        mimeType = entry.record.mimeType,
                        caption = "${formatBytes(entry.record.plainSize)} · ${formatModified(entry.record.modifiedAtMillis)}",
                        picked = isPicked,
                        thumbnail = vaultCover(graph, entry.record),
                        compact = compact,
                        activePreview = isVid && isActive,
                        previewContent = if (isVid && isActive) {
                            {
                                TileVideoPreview(
                                    graph = graph,
                                    record = entry.record,
                                    modifier = Modifier.fillMaxSize(),
                                    showDashes = false,
                                )
                            }
                        } else null,
                        onClick = { onOpen(entry) },
                        onLongClick = { onPick(entry) },
                    )
                }
            }
    }
}
}

@Composable
private fun EntryList(
    entries: List<VaultEntry>,
    selected: Set<String>,
    graph: AppGraph,
    onOpen: (VaultEntry) -> Unit,
    onPick: (VaultEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val isScrolling = listState.isScrollInProgress

    val candidateFocusedKey by remember(entries) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null
            val vCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val videoKeys = entries.filter { it is VaultEntry.File && kindOf(it.record) == ViewerKind.VIDEO }
                .map { keyOf(it) }
                .toSet()
            if (videoKeys.isEmpty()) return@derivedStateOf null

            visibleItems.filter { it.key in videoKeys }
                .minByOrNull { item ->
                    val itemCenter = item.offset + (item.size / 2)
                    kotlin.math.abs(itemCenter - vCenter)
                }?.key as? String
        }
    }

    var activePreviewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidateFocusedKey, isScrolling) {
        if (isScrolling) {
            activePreviewKey = null
        } else {
            delay(160L)
            activePreviewKey = candidateFocusedKey
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, top = ArcaneSpace.xs, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { keyOf(it) }) { entry ->
            val file = entry as? VaultEntry.File
            val isVid = file != null && kindOf(file.record) == ViewerKind.VIDEO
            val isPicked = keyOf(entry) in selected
            val isActive = keyOf(entry) == activePreviewKey
            EntryRow(
                name = entry.name,
                caption = when (entry) {
                    is VaultEntry.Folder -> entry.caption()
                    is VaultEntry.File -> "${formatBytes(entry.record.plainSize)} · ${formatModified(entry.record.modifiedAtMillis)}"
                },
                glyph = dev.arcane.ui.components.glyphFor(entry.name, file?.record?.mimeType),
                picked = isPicked,
                thumbnail = file?.let { vaultCover(graph, it.record) },
                tint = if (file == null) ArcanePalette.VioletDim else ArcanePalette.tintFor(file.record.mimeType, file.name),
                activePreview = isVid && isActive,
                previewContent = if (isVid && isActive) {
                    {
                        TileVideoPreview(
                            graph = graph,
                            record = file!!.record,
                            modifier = Modifier.fillMaxSize(),
                            showDashes = false,
                        )
                    }
                } else null,
                onClick = { onOpen(entry) },
                onLongClick = { onPick(entry) },
            )
        }
    }
}

@Composable
private fun BigTileFeed(
    entries: List<VaultEntry>,
    selected: Set<String>,
    graph: AppGraph,
    onOpen: (VaultEntry) -> Unit,
    onPick: (VaultEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val isScrolling = listState.isScrollInProgress

    val candidateFocusedKey by remember(entries) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf null
            val vCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val videoKeys = entries.filter { it is VaultEntry.File && kindOf(it.record) == ViewerKind.VIDEO }
                .map { keyOf(it) }
                .toSet()
            if (videoKeys.isEmpty()) return@derivedStateOf null

            visibleItems.filter { it.key in videoKeys }
                .minByOrNull { item ->
                    val itemCenter = item.offset + (item.size / 2)
                    kotlin.math.abs(itemCenter - vCenter)
                }?.key as? String
        }
    }

    var activePreviewKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidateFocusedKey, isScrolling) {
        if (isScrolling) {
            activePreviewKey = null
        } else {
            delay(160L)
            activePreviewKey = candidateFocusedKey
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, top = ArcaneSpace.xs, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Vault large cards feed" },
    ) {
        items(entries, key = { keyOf(it) }) { entry ->
            val isPicked = keyOf(entry) in selected
            val isActive = keyOf(entry) == activePreviewKey
            when (entry) {
                is VaultEntry.Folder -> BigFolderCard(
                    folder = entry,
                    picked = isPicked,
                    onClick = { onOpen(entry) },
                    onLongClick = { onPick(entry) },
                )
                is VaultEntry.File -> BigFileCard(
                    file = entry,
                    graph = graph,
                    picked = isPicked,
                    activePreview = isActive,
                    onClick = { onOpen(entry) },
                    onLongClick = { onPick(entry) },
                )
            }
        }
    }
}

@Composable
private fun BigFolderCard(
    folder: VaultEntry.Folder,
    picked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .arcaneCard(
                shape = RoundedCornerShape(ArcaneRadius.card),
                selected = picked,
            )
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = "${folder.name}, ${folder.caption()}"
                this.selected = picked
            }
            .padding(ArcaneSpace.md),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(ArcaneRadius.field)),
                contentAlignment = Alignment.Center,
            ) {
                FolderCover(Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ArcanePalette.TextHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    folder.caption(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextFaint,
                )
            }
        }
        if (picked) {
            PickDot(picked = true, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun BigFileCard(
    file: VaultEntry.File,
    graph: AppGraph,
    picked: Boolean,
    activePreview: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val isVideo = remember(file.record) { kindOf(file.record) == ViewerKind.VIDEO }
    val cover = vaultCover(graph, file.record)
    val ext = remember(file.name) { extensionOf(file.name).uppercase() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .arcaneCard(
                shape = RoundedCornerShape(ArcaneRadius.card),
                selected = picked,
            )
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = "${file.name}, ${formatBytes(file.record.plainSize)}"
                this.selected = picked
            },
    ) {
        // Thumbnail cover always sits underneath
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(ArcanePalette.tintFor(file.record.mimeType, file.name), ArcanePalette.Ink2))),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Cover(cover, Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = glyphFor(file.name, file.record.mimeType),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // Live silent looping video preview when centered in viewport
        if (isVideo && activePreview) {
            TileVideoPreview(
                graph = graph,
                record = file.record,
                modifier = Modifier.fillMaxSize(),
                showDashes = true,
            )
        }

        // Dark gradient scrim at the bottom for crisp legibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    ),
                ),
        )

        // Top badges: extension / video pill and selection dot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ArcaneSpace.sm, end = ArcaneSpace.sm, top = 16.dp, bottom = 4.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ext.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(ArcanePill)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), ArcanePill)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isVideo) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = ArcanePalette.Cyan,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Text(
                            text = if (isVideo && activePreview) "$ext · PREVIEW" else ext,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }

            if (picked) {
                PickDot(picked = true)
            }
        }

        // Centered play indicator when video is not actively previewing
        if (isVideo && !activePreview) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Bottom file details
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = ArcaneSpace.sm, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatBytes(file.record.plainSize)} · ${formatModified(file.record.modifiedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TileVideoPreview(
    graph: AppGraph,
    record: FileRecord,
    modifier: Modifier = Modifier,
    showDashes: Boolean = false,
) {
    val context = LocalContext.current
    val fileKey = remember(record) { Hex.lower(record.fileId) }

    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 3500,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 800,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    var firstFrameRendered by remember(fileKey) { mutableStateOf(false) }
    var activeSegment by remember(fileKey) { mutableIntStateOf(0) }
    var checkPoints by remember(fileKey) { mutableStateOf<List<Long>>(emptyList()) }

    val player = remember(fileKey) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playbackSources))
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(graph.repository.playbackUri(record.fileId)))
                prepare()
                playWhenReady = true
            }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        graph.registerPlayer(player)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (checkPoints.isNotEmpty()) {
                        activeSegment = (activeSegment + 1) % checkPoints.size
                        player.seekTo(checkPoints[activeSegment])
                        player.play()
                    }
                }
            }
        }
        player.addListener(listener)

        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (player.isPlaying) player.pause()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (checkPoints.isNotEmpty()) player.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            graph.unregisterPlayer(player)
            player.stop()
            player.release()
        }
    }

    // Timeline highlight calculation and dynamic cycling loop
    LaunchedEffect(player) {
        var attempts = 0
        while (checkPoints.isEmpty() && attempts < 15) {
            val dur = player.duration
            if (dur > 0 && dur != C.TIME_UNSET) {
                checkPoints = if (dur < 4_000L) {
                    listOf(0L)
                } else {
                    listOf(
                        kotlin.math.max(1_500L, (dur * 0.15).toLong()), // 15%: skips logo/intro/black frames
                        (dur * 0.40).toLong(),                          // 40%: early core scene
                        (dur * 0.65).toLong(),                          // 65%: middle climax/action
                        kotlin.math.min(dur - 1_200L, (dur * 0.85).toLong()), // 85%: late scene
                    )
                }
                break
            }
            delay(40L)
            attempts++
        }

        if (checkPoints.isEmpty()) {
            checkPoints = listOf(1_500L)
        }

        activeSegment = 0
        player.seekTo(checkPoints[0])
        if (!player.isPlaying) player.play()

        val segmentDurationMs = 2600L
        while (true) {
            delay(segmentDurationMs)
            if (checkPoints.size > 1) {
                activeSegment = (activeSegment + 1) % checkPoints.size
                val targetMs = checkPoints[activeSegment]
                player.seekTo(targetMs)
                if (!player.isPlaying) player.play()
            }
        }
    }

    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "previewAlpha",
    )

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(
                    dev.arcane.R.layout.arcane_tile_player,
                    null
                ) as PlayerView).apply {
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }
            },
            onRelease = { view ->
                view.player = null
            },
        )

            // Sleek multi-segment story dashes at the top edge of video card
            if (showDashes && checkPoints.size > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                            ),
                        ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(checkPoints.size) { index ->
                        val isCurrent = index == activeSegment
                        val isPassed = index < activeSegment
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(ArcanePill)
                                .background(
                                    when {
                                        isCurrent -> ArcanePalette.Cyan
                                        isPassed -> ArcanePalette.Cyan.copy(alpha = 0.55f)
                                        else -> Color.White.copy(alpha = 0.30f)
                                    }
                                ),
                        )
                    }
                }
            }
        }
    }

/**
 * A cover for one file, decrypted off the main thread and dropped when the tile leaves composition.
 *
 * The cache is the source of the instant answer — scroll back into a tile and its bitmap appears in the same
 * frame — while a miss starts one decode. [AppGraph.lockNow] bumps the cache generation, so a decode that
 * outlives the lock cannot put its plaintext back into a grid that no longer owns a key.
 */
@Composable
private fun vaultCover(graph: AppGraph, record: FileRecord): Any? {
    val key = remember(record.fileId) { Hex.lower(record.fileId) }
    val cover by produceState(initialValue = graph.thumbnails.peek(record), key) {
        if (value == null) value = graph.thumbnails.load(record)
    }
    return cover
}

@Composable
private fun VaultSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
    ) {
        repeat(3) { TileSkeleton() }
    }
}

@Composable
private fun EmptyFolder(
    isRoot: Boolean,
    onAddFiles: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isRoot) {
                Image(
                    painter = painterResource(R.drawable.arcane_shield),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(ArcaneSpace.md))
        Text(
            text = if (isRoot) "The Vault is empty" else "This folder is empty",
            style = MaterialTheme.typography.titleLarge,
            color = ArcanePalette.TextHigh,
        )
        Spacer(Modifier.height(ArcaneSpace.xs))
        Text(
            text = if (isRoot) {
                "Imported files are encrypted with AES-256-GCM and fragmented across scattered blobs. Nothing touches storage in plaintext."
            } else {
                "No encrypted items in this folder. Add files from device storage or create subfolders."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ArcanePalette.TextMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(ArcaneSpace.lg))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArcaneButton(
                label = "Add files",
                icon = Icons.Rounded.Add,
                onClick = onAddFiles,
                filled = true,
            )
            ArcaneButton(
                label = "New folder",
                icon = Icons.Rounded.CreateNewFolder,
                onClick = onNewFolder,
                filled = false,
            )
            ArcaneButton(
                label = "New note",
                icon = Icons.Rounded.EditNote,
                onClick = onNewTextFile,
                filled = false,
            )
        }
    }
}

@Composable
private fun NoMatches(onClear: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        ArcaneEmptyState(
            icon = Icons.Rounded.Search,
            title = "No name matches",
            body = "Only this folder is searched, because the tree is decrypted as it is opened.",
            actionLabel = "Clear",
            onAction = onClear,
            actionIcon = Icons.Rounded.Close,
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    totalCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    visible: Boolean,
    canExport: Boolean,
    onClear: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = visible,
        transitionSpec = {
            fadeIn(tween(ArcaneMotion.quick)) + slideInVertically(tween(ArcaneMotion.slow)) { it } togetherWith
                fadeOut(tween(ArcaneMotion.quick)) + slideOutVertically(tween(ArcaneMotion.quick)) { it }
        },
        label = "selection",
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.sm),
    ) { show ->
        if (show) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, ArcanePill, spotColor = Color.Black.copy(alpha = 0.8f))
                    .clip(ArcanePill)
                    .background(ArcanePalette.Ink2)
                    .border(1.dp, ArcanePalette.EdgeStrong, ArcanePill)
                    .padding(horizontal = ArcaneSpace.md, vertical = 6.dp)
                    .semantics {
                        contentDescription = "$count of $totalCount selected"
                        // Long-press selection changes the bar without moving focus, so the count is the one
                        // thing that has to be announced as it changes.
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
                ) {
                    ArcaneAction(
                        icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                        hint = if (allSelected) "Deselect all" else "Select all",
                        onClick = onToggleSelectAll,
                        target = 40.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "$count / $totalCount",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    ArcaneAction(Icons.AutoMirrored.Rounded.DriveFileMove, "Move", onMove, target = 40.dp)
                    ArcaneAction(
                        Icons.Rounded.Edit,
                        "Rename",
                        onRename,
                        target = 40.dp,
                        enabled = count == 1,
                    )
                    if (canExport) {
                        ArcaneAction(
                            Icons.Rounded.Save,
                            "Export",
                            onExport,
                            target = 40.dp,
                        )
                    }
                    ArcaneAction(Icons.Rounded.Delete, "Delete", onDelete, target = 40.dp, tint = MaterialTheme.colorScheme.error)
                    ArcaneAction(Icons.Rounded.Close, "Cancel selection", onClear, target = 40.dp)
                }
            }
        }
    }
}

private fun keyOf(entry: VaultEntry): String = when (entry) {
    is VaultEntry.Folder -> "d:" + entry.record.id
    is VaultEntry.File -> "f:" + Hex.lower(entry.record.fileId)
}

private fun VaultEntry.Folder.caption(): String = when (itemCount) {
    0 -> "Empty"
    1 -> "1 item"
    else -> "$itemCount items"
}

private fun toggle(entry: VaultEntry, current: Set<String>, publish: (Set<String>) -> Unit) {
    val key = keyOf(entry)
    publish(if (key in current) current - key else current + key)
}

private fun openEntry(
    entry: VaultEntry,
    onScreen: List<VaultEntry>,
    scope: kotlinx.coroutines.CoroutineScope,
    graph: AppGraph,
) {
    when (entry) {
        is VaultEntry.Folder -> scope.launch { graph.repository.show(entry.record.id) }
        is VaultEntry.File -> graph.view(
            entry.record,
            onScreen.filterIsInstance<VaultEntry.File>().map { it.record },
        )
    }
}

/** One folder up: the trail's parent, or the root when the trail holds only the folder itself. */
private fun VaultListing.parentToGo(): String? = if (trail.size <= 1) null else trail[trail.size - 2].id

private fun List<VaultEntry>.matching(query: String): List<VaultEntry> {
    if (query.isBlank()) return this
    val needle = query.trim().lowercase()
    return filter { it.name.lowercase().contains(needle) }
}

/** Folders first in every order; a mixed row of folders and files is a list people lose their place in. */
private fun VaultListing.arranged(sort: Sort): List<VaultEntry> {
    val folders = entries.filterIsInstance<VaultEntry.Folder>()
    val files = entries.filterIsInstance<VaultEntry.File>()
    val orderedFolders = folders.sortedBy { it.name.lowercase() }
    val orderedFiles = when (sort) {
        Sort.Name -> files.sortedBy { it.name.lowercase() }
        Sort.Newest -> files.sortedByDescending { it.record.modifiedAtMillis }
        Sort.Largest -> files.sortedByDescending { it.record.plainSize }
        Sort.Kind -> files.sortedWith(
            compareBy<VaultEntry.File> { it.record.mimeType.orEmpty().ifEmpty { "~" } }
                .thenBy { it.name.substringAfterLast('.', "").lowercase() }
                .thenBy { it.name.lowercase() }
        )
    }
    return orderedFolders + orderedFiles
}

private const val CHUNK = dev.arcane.vault.format.VaultFormat.CHUNK_PAYLOAD_BYTES.toLong()
