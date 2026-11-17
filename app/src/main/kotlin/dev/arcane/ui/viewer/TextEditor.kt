package dev.arcane.ui.viewer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.ArcaneSheet
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.vault.index.FileRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Text Editor for Arcane Encrypted Vault.
 *
 * Provides a clean, responsive, distraction-free editing surface for .txt, .md, and code files.
 * Text is kept purely in RAM and encrypted directly with AES-256-GCM into VaultRealm chunks when saved,
 * preventing any plaintext leakage to persistent flash storage.
 */
@Composable
fun TextEditor(
    graph: AppGraph,
    record: FileRecord,
    onBack: () -> Unit,
    onRecordUpdated: (FileRecord) -> Unit = {},
    onSwitchToReader: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentRecord by remember(record.fileId) { mutableStateOf(record) }

    var loading by remember(record.fileId) { mutableStateOf(record.plainSize > 0L) }
    var loadError by remember(record.fileId) { mutableStateOf<String?>(null) }

    var textValue by remember(record.fileId) { mutableStateOf(TextFieldValue("")) }
    var initialText by remember(record.fileId) { mutableStateOf("") }
    val isDirty by remember { derivedStateOf { textValue.text != initialText } }

    var mono by remember { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(true) }
    var showLineNumbers by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    // Decrypt text directly into RAM
    LaunchedEffect(record.fileId) {
        if (record.plainSize == 0L) {
            loading = false
            initialText = ""
            textValue = TextFieldValue("")
            delay(150)
            runCatching { focusRequester.requestFocus() }
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        val outcome = runCatching { graph.repository.readText(record.fileId) }
        outcome.onSuccess { content ->
            initialText = content
            textValue = TextFieldValue(content)
            loading = false
        }.onFailure { err ->
            loadError = err.message ?: "Could not decrypt text."
            loading = false
        }
    }

    // Intercept back gesture if unsaved changes exist
    BackHandler(enabled = isDirty) {
        showExitConfirm = true
    }

    val saveAction: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                val updated = graph.repository.saveTextFile(
                    fileId = currentRecord.fileId,
                    name = currentRecord.name,
                    content = textValue.text,
                    folderId = currentRecord.folderId,
                )
                saving = false
                if (updated != null) {
                    currentRecord = updated
                    initialText = textValue.text
                    onRecordUpdated(updated)
                    savedNotice = true
                    Toast.makeText(context, "Encrypted & saved to vault", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(savedNotice) {
        if (savedNotice) {
            delay(2000)
            savedNotice = false
        }
    }

    val lineCount = remember(textValue.text) {
        if (textValue.text.isEmpty()) 1 else textValue.text.count { it == '\n' } + 1
    }
    val charCount = textValue.text.length
    val (cursorLine, cursorCol) = remember(textValue.text, textValue.selection) {
        val pos = textValue.selection.start.coerceIn(0, textValue.text.length)
        val prefix = textValue.text.take(pos)
        val line = prefix.count { it == '\n' } + 1
        val lastNewline = prefix.lastIndexOf('\n')
        val col = if (lastNewline >= 0) pos - lastNewline else pos + 1
        line to col
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Top Toolbar
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
            ArcaneAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                hint = "Back",
                onClick = {
                    if (isDirty) showExitConfirm = true else onBack()
                },
                target = 48.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentRecord.name,
                        style = ArcaneType.Scale.titleMedium,
                        color = ArcanePalette.TextHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isDirty) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ArcanePalette.Amber.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Edited",
                                style = ArcaneType.Scale.labelSmall.copy(fontSize = 10.sp),
                                color = ArcanePalette.Amber,
                            )
                        }
                    } else if (savedNotice) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ArcanePalette.Mint.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Saved",
                                style = ArcaneType.Scale.labelSmall.copy(fontSize = 10.sp),
                                color = ArcanePalette.Mint,
                            )
                        }
                    }
                }
                Text(
                    text = "Ln $cursorLine, Col $cursorCol · $lineCount lines · $charCount chars",
                    style = ArcaneType.Scale.labelSmall,
                    color = ArcanePalette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onSwitchToReader != null) {
                ArcaneAction(
                    icon = Icons.Rounded.Visibility,
                    hint = "View mode",
                    onClick = onSwitchToReader,
                    target = 48.dp,
                    tint = ArcanePalette.TextMedium,
                )
            }
            ArcaneAction(
                icon = when {
                    saving -> Icons.Rounded.HourglassEmpty
                    savedNotice -> Icons.Rounded.Check
                    else -> Icons.Rounded.Save
                },
                hint = "Save changes to vault",
                onClick = saveAction,
                enabled = isDirty && !saving,
                tint = if (isDirty) MaterialTheme.colorScheme.primary else ArcanePalette.TextFaint,
                target = 48.dp,
            )
        }

        // Secondary Options Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
        ) {
            ArcaneChip(
                label = if (mono) "Monospace" else "Proportional",
                onClick = { mono = !mono },
                selected = mono,
            )
            ArcaneChip(
                label = if (wrap) "Wrap" else "No wrap",
                onClick = { wrap = !wrap },
                selected = wrap,
            )
            ArcaneChip(
                label = if (showLineNumbers) "Line #" else "No Line #",
                onClick = { showLineNumbers = !showLineNumbers },
                selected = showLineNumbers,
            )
        }

        // Main Editing Canvas
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            loadError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = loadError ?: "Error loading file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(ArcaneSpace.gutter),
                    )
                }
            }
            else -> {
                val textStyle = (if (mono) ArcaneType.Mono else ArcaneType.Reader).copy(
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = ArcanePalette.TextHigh,
                )

                val scrollModifier = if (wrap) {
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                } else {
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .horizontalScroll(horizontalScroll)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xs)
                        .clip(RoundedCornerShape(ArcaneRadius.field))
                        .background(ArcanePalette.Ink2)
                        .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.field))
                ) {
                    Row(
                        modifier = scrollModifier
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (showLineNumbers) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .width(36.dp),
                            ) {
                                for (i in 1..lineCount) {
                                    Text(
                                        text = i.toString(),
                                        style = ArcaneType.Mono.copy(
                                            fontSize = 12.sp,
                                            lineHeight = 22.sp,
                                        ),
                                        color = if (i == cursorLine) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(ArcanePalette.Edge)
                            )
                            Spacer(Modifier.width(10.dp))
                        }

                        BasicTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            textStyle = textStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                autoCorrectEnabled = false,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 400.dp)
                                .focusRequester(focusRequester),
                        )
                    }
                }
            }
        }
    }

    // Unsaved Changes Confirmation Dialog
    ArcaneSheet(
        visible = showExitConfirm,
        onDismiss = { showExitConfirm = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ArcaneSpace.md)
                .padding(bottom = ArcaneSpace.md),
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
        ) {
            Text(
                text = "Unsaved changes",
                style = MaterialTheme.typography.titleLarge,
                color = ArcanePalette.TextHigh,
            )
            Text(
                text = "You have unsaved edits in \"${currentRecord.name}\". What would you like to do?",
                style = MaterialTheme.typography.bodyMedium,
                color = ArcanePalette.TextMedium,
            )
            Row(
                modifier = Modifier.padding(top = ArcaneSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
            ) {
                ArcaneButton(
                    label = "Discard",
                    filled = false,
                    onClick = {
                        showExitConfirm = false
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                )
                ArcaneButton(
                    label = "Save & Exit",
                    icon = Icons.Rounded.Save,
                    onClick = {
                        showExitConfirm = false
                        scope.launch {
                            val updated = graph.repository.saveTextFile(
                                fileId = currentRecord.fileId,
                                name = currentRecord.name,
                                content = textValue.text,
                                folderId = currentRecord.folderId,
                            )
                            if (updated != null) {
                                onRecordUpdated(updated)
                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                            }
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
