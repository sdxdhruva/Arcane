package dev.arcane.ui.vault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import dev.arcane.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.data.AppPrefs
import dev.arcane.data.Transfer
import dev.arcane.data.VaultEntry
import dev.arcane.data.VaultListing
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.ArcaneEmptyState
import dev.arcane.ui.components.Crumb
import dev.arcane.ui.components.OutlinedField
import dev.arcane.ui.components.DetailRow
import dev.arcane.ui.components.FolderChoiceList
import dev.arcane.ui.components.PromptSheetContent
import dev.arcane.ui.components.SheetAction
import dev.arcane.ui.components.TileSkeleton
import dev.arcane.ui.components.TransferMeter
import dev.arcane.ui.components.ArcaneSheet
import dev.arcane.ui.components.EntryRow
import dev.arcane.ui.components.FileTile
import dev.arcane.ui.components.FolderTile
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.util.formatBytes
import dev.arcane.ui.util.formatModified
import dev.arcane.vault.format.Hex
import dev.arcane.vault.index.FileRecord
import dev.arcane.vault.realm.PendingItem
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

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.GRID_COMFORTABLE) }
    var sort by rememberSaveable { mutableStateOf(Sort.Name) }
    var sorting by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var reels by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var quiet by remember { mutableStateOf(graph.ringer.hasAccess) }
    var choices by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingItem>>(emptyList()) }

    // Back unwinds this screen before it unwinds the app: the sheet, then the selection, then the search,
    // then the folder. Without this, back inside a subfolder finishes the activity and looks like a crash.
    BackHandler(
        enabled = confirmingDelete || menu || moving || sorting || prompt != null || searching || selected.isNotEmpty() || listing.folderId != null,
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
    val picked = remember(shown, selected) { shown.filter { keyOf(it) in selected } }

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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrowserHeader(
                listing = listing,
                count = shown.size,
                bytes = listing.totalBytes,
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
                onMenu = { menu = true },
            )

            if (listing.trail.isNotEmpty()) {
                CrumbTrail(listing) { folderId -> scope.launch { graph.repository.show(folderId) } }
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
                )

                shown.isEmpty() -> NoMatches(onClear = { query = "" })

                viewMode != ViewMode.LIST -> TileGrid(
                    entries = shown,
                    selected = selected,
                    graph = graph,
                    compact = viewMode == ViewMode.GRID_COMPACT,
                    onOpen = { entry -> openEntry(entry, shown, scope, graph) },
                    onPick = { toggle(it, selected) { selected = it } },
                )

                else -> EntryList(
                    entries = shown,
                    selected = selected,
                    graph = graph,
                    onOpen = { entry -> openEntry(entry, shown, scope, graph) },
                    onPick = { toggle(it, selected) { selected = it } },
                )
            }
        }

        SelectionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            count = picked.size,
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
                            scope.launch {
                                graph.repository.delete(picked.filterIsInstance<VaultEntry.File>().map { it.record.fileId })
                                picked.filterIsInstance<VaultEntry.Folder>().forEach { graph.repository.deleteFolder(it.record.id) }
                                selected = emptySet()
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
                listing.trail.lastOrNull()?.name ?: "Vault Options",
                style = MaterialTheme.typography.titleLarge,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs),
            )
            SheetAction(
                label = when (viewMode) {
                    ViewMode.GRID_COMFORTABLE -> "Layout: Big Grid"
                    ViewMode.GRID_COMPACT -> "Layout: Small Grid"
                    ViewMode.LIST -> "Layout: List View"
                },
                subtitle = "Switch between large cards, compact grid, and list view",
                icon = when (viewMode) {
                    ViewMode.GRID_COMFORTABLE -> Icons.Rounded.GridView
                    ViewMode.GRID_COMPACT -> Icons.Rounded.Dashboard
                    ViewMode.LIST -> Icons.AutoMirrored.Rounded.ViewList
                },
                cover = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ) {
                menu = false
                viewMode = when (viewMode) {
                    ViewMode.GRID_COMFORTABLE -> ViewMode.GRID_COMPACT
                    ViewMode.GRID_COMPACT -> ViewMode.LIST
                    ViewMode.LIST -> ViewMode.GRID_COMFORTABLE
                }
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
            Text(
                "Move to",
                style = MaterialTheme.typography.titleLarge,
                color = ArcanePalette.TextHigh,
                modifier = Modifier.padding(horizontal = ArcaneSpace.md, vertical = ArcaneSpace.xs),
            )
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
                    listing.trail.lastOrNull()?.name ?: "This folder",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ArcanePalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (busy) "Reading…" else "$count items · ${formatBytes(bytes)}",
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
                label = if (searchAll) "Search all folders" else "Filter this folder",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
            ) {
                ArcaneChip(
                    label = "This folder",
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
        Crumb("Files", current = false) { onGo(null) }
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
private fun TileGrid(
    entries: List<VaultEntry>,
    selected: Set<String>,
    graph: AppGraph,
    compact: Boolean,
    onOpen: (VaultEntry) -> Unit,
    onPick: (VaultEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (compact) 104.dp else 152.dp),
        contentPadding = PaddingValues(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, top = ArcaneSpace.xs, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else ArcaneSpace.tileGap),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else ArcaneSpace.tileGap),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Vault contents" },
    ) {
        items(entries, key = { keyOf(it) }) { entry ->
            when (entry) {
                is VaultEntry.Folder -> FolderTile(
                    name = entry.name,
                    caption = entry.caption(),
                    picked = keyOf(entry) in selected,
                    compact = compact,
                    onClick = { onOpen(entry) },
                    onLongClick = { onPick(entry) },
                )

                is VaultEntry.File -> FileTile(
                    name = entry.name,
                    mimeType = entry.record.mimeType,
                    caption = "${formatBytes(entry.record.plainSize)} · ${formatModified(entry.record.modifiedAtMillis)}",
                    picked = keyOf(entry) in selected,
                    thumbnail = vaultCover(graph, entry.record),
                    compact = compact,
                    onClick = { onOpen(entry) },
                    onLongClick = { onPick(entry) },
                )
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
    LazyColumn(
        contentPadding = PaddingValues(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { keyOf(it) }) { entry ->
            val file = entry as? VaultEntry.File
            EntryRow(
                name = entry.name,
                caption = when (entry) {
                    is VaultEntry.Folder -> entry.caption()
                    is VaultEntry.File -> "${formatBytes(entry.record.plainSize)} · ${formatModified(entry.record.modifiedAtMillis)}"
                },
                glyph = dev.arcane.ui.components.glyphFor(entry.name, file?.record?.mimeType),
                picked = keyOf(entry) in selected,
                thumbnail = file?.let { vaultCover(graph, it.record) },
                tint = if (file == null) ArcanePalette.VioletDim else ArcanePalette.tintFor(file.record.mimeType, file.name),
                onClick = { onOpen(entry) },
                onLongClick = { onPick(entry) },
            )
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
            text = if (isRoot) "Your vault is empty" else "This folder is empty",
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
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
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
        modifier = modifier.fillMaxWidth(),
    ) { show ->
        if (show) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.sm)
                    .semantics {
                        contentDescription = "$count selected"
                        // Long-press selection changes the bar without moving focus, so the count is the one
                        // thing that has to be announced as it changes.
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
            ) {
                Text(
                    "$count",
                    style = ArcaneType.Scale.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(end = 2.dp),
                )
                ArcaneAction(Icons.AutoMirrored.Rounded.DriveFileMove, "Move", onMove, target = 48.dp)
                ArcaneAction(
                    Icons.Rounded.Edit,
                    "Rename",
                    onRename,
                    target = 48.dp,
                    enabled = count == 1,
                )
                if (canExport) {
                    ArcaneAction(
                        Icons.Rounded.Save,
                        "Export",
                        onExport,
                        target = 48.dp,
                    )
                }
                Spacer(Modifier.weight(1f))
                ArcaneAction(Icons.Rounded.Delete, "Delete", onDelete, target = 48.dp, tint = MaterialTheme.colorScheme.error)
                ArcaneAction(Icons.Rounded.Close, "Cancel selection", onClear, target = 48.dp)
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
