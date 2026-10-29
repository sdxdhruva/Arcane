package dev.arcane.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.ui.theme.arcaneCard

/**
 * The one overlay container: a scrim over the screen and a card that rises from the bottom.
 *
 * Not `ModalBottomSheet`, because that component owns its own drag handling, its own insets and its own
 * shape, while every sheet in this app is a short list with one or two actions. Stating the container once
 * is cheaper than arguing with a library per screen — and it means a sheet over the vault looks exactly like a
 * sheet over Files, which is the point.
 */
@Composable
fun ArcaneSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back closes the sheet before it closes anything underneath it. It is also the only way an accessibility
    // service has of dismissing one, since the scrim behind it carries no label a reader could announce.
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ArcaneMotion.quick)) +
            slideInVertically(tween(ArcaneMotion.slow)) { it / 2 },
        exit = fadeOut(tween(ArcaneMotion.quick)) +
            slideOutVertically(tween(ArcaneMotion.quick)) { it / 2 },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ArcanePalette.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(start = ArcaneSpace.gutter, end = ArcaneSpace.gutter, bottom = ArcaneSpace.tight)
                    .arcaneCard(shape = RoundedCornerShape(ArcaneRadius.sheet), raised = true)
                    // Swallows the tap that lands on the card itself, so only the scrim behind it dismisses.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { },
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ArcanePalette.EdgeStrong)
                    )
                }
                content()
            }
        }
    }
}

/** A question with one text answer: rename, new folder. */
@Composable
fun PromptSheetContent(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    reject: String? = null,
    supporting: String? = null,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.md)
            .padding(bottom = ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = ArcanePalette.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedField(
            value = text,
            onChange = { text = it },
            label = label,
            onSubmit = { if (text.isNotBlank() && reject == null) onConfirm(text.trim()) },
        )
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = ArcanePalette.TextFaint)
        }
        reject?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs)) {
            ArcaneButton(
                label = "Cancel",
                filled = false,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            ArcaneButton(
                label = confirmLabel,
                enabled = text.isNotBlank() && reject == null,
                onClick = { onConfirm(text.trim()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The single-line input a prompt needs, in the same box language as the passphrase field. */
@Composable
fun OutlinedField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    val shape = RoundedCornerShape(ArcaneRadius.field)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ArcanePalette.TextFaint,
            maxLines = 1,
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = ArcaneType.Mono.fontFamily,
                fontSize = 14.sp,
                color = ArcanePalette.TextHigh,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, ArcanePalette.EdgeStrong, shape)
                .padding(horizontal = 14.dp, vertical = 15.dp),
        )
    }
}

/** A list of places a selection could go, with each path spelled the way the breadcrumb spells it. */
@Composable
fun FolderChoiceList(
    choices: List<Pair<String?, String>>,
    current: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ArcaneSpace.md),
    ) {
        items(choices, key = { it.first ?: "root" }) { (id, path) ->
            val here = id == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !here) { onPick(id) }
                    .padding(horizontal = ArcaneSpace.md, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
            ) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (here) ArcanePalette.TextFaint else ArcanePalette.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (here) {
                    Text(
                        "You are here",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcanePalette.TextFaint,
                    )
                }
            }
        }
    }
}

/** One labelled line in a detail sheet. Values are left-aligned against a fixed label column. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = ArcanePalette.TextFaint,
            modifier = Modifier.widthIn(min = 74.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ArcanePalette.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row that performs one action with a premium icon badge, title, and optional descriptive subtitle. */
@Composable
fun SheetAction(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    cover: Color? = null,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .semantics { if (checked) selected = true }
            .padding(horizontal = ArcaneSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
    ) {
        val plateBg = when {
            destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            cover != null -> cover
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val iconTint = when {
            destructive -> MaterialTheme.colorScheme.error
            cover != null -> ArcanePalette.TextHigh
            else -> MaterialTheme.colorScheme.primary
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(plateBg),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (destructive) MaterialTheme.colorScheme.error else ArcanePalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.72f) else ArcanePalette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checked) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
