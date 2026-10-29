package dev.arcane.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace

/**
 * A 48dp icon target with the app's one press response. [hint] is what TalkBack reads; every control takes
 * it, because an icon-only button without a label is a guess rather than a control.
 */
@Composable
fun ArcaneAction(
    icon: ImageVector,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    target: Dp = 48.dp,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val shrink by animateFloatAsState(if (pressed) 0.92f else 1f, label = "action-press")
    Box(
        modifier = modifier
            .size(target)
            .clip(CircleShape)
            .semantics { contentDescription = hint }
            .clickable(enabled = enabled, interactionSource = interactions, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else ArcanePalette.TextFaint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = shrink
                    scaleY = shrink
                },
        )
    }
}

/** A pill for filters and modes. Selected is a filled container, never a colour change on the text alone. */
@Composable
fun ArcaneChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(ArcaneMotion.quick),
        label = "chip",
    )
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else ArcanePalette.Edge, shape)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(enabled = enabled, interactionSource = interactions, indication = null) { onClick() }
            // Selection is drawn as a filled container, which a screen reader cannot see: without this, the
            // "Scripts on" chip reads as the same button whichever state it is in.
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else ArcanePalette.TextMedium,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else ArcanePalette.TextMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The skeleton every browser paints before its first listing arrives. A spinner says "wait"; this says
 * "here is where your things will be", which is the right message for a file manager.
 */
@Composable
fun Shimmer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val animated = ArcaneMotion.durationScale() != 0f
    val transition = rememberInfiniteTransition(label = "shimmer")
    val travel by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-travel",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val lifted = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier
            .clip(shape)
            .background(base)
            .clearAndSetSemantics { },
    ) {
        if (animated) {
            val left = travel * 700f - 300f
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, lifted, Color.Transparent),
                            start = Offset(left, 0f),
                            end = Offset(left + 300f, 300f),
                        )
                    )
            )
        }
    }
}

/** Progress phrased in bytes rather than a bare bar: a 4 GiB import has to say how far it got. */
@Composable
fun TransferMeter(
    fraction: Float,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(ArcaneSpace.sm))
            Text(
                text = if (failed) "Failed" else detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else ArcanePalette.TextFaint,
                maxLines = 1,
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * An empty state with one action. A vault screen is never blank: an empty frame reads as a failure, and a
 * single clear step is also the least alarming thing to show someone who just typed a passphrase that opened
 * a room they did not expect.
 */
@Composable
fun ArcaneEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.lg, vertical = ArcaneSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, ArcanePalette.EdgeStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(ArcaneSpace.tight))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = ArcanePalette.TextHigh,
            maxLines = 2,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = ArcanePalette.TextMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(ArcaneSpace.xs))
            ArcaneButton(label = actionLabel, icon = actionIcon, onClick = onAction)
        }
    }
}

/**
 * The filled button. Not a stock M3 `Button`: its stadium shape and text metrics sit 2dp off the rhythm the
 * rest of the app keeps.
 */
@Composable
fun ArcaneButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "button")
    val shape = RoundedCornerShape(14.dp)
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    filled && enabled -> MaterialTheme.colorScheme.primary
                    filled -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (filled) 0.dp else 1.dp,
                color = if (filled) Color.Transparent else ArcanePalette.EdgeStrong,
                shape = shape,
            )
            .clickable(enabled = enabled, interactionSource = interactions, indication = null) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (filled && enabled) onAccent else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(ArcaneSpace.xs))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled && enabled) onAccent else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The "picked" mark, so multi-select looks identical in a grid, a row and a sheet. */
@Composable
fun PickDot(picked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (picked) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (picked) MaterialTheme.colorScheme.primary else ArcanePalette.EdgeStrong,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
