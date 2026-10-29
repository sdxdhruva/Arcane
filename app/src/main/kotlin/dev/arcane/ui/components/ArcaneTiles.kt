package dev.arcane.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.arcaneCard
import dev.arcane.ui.util.extensionOf

/** The glyph for a kind of content, chosen from the extension rather than the mime type the index happens to hold. */
fun glyphFor(name: String, mimeType: String?): ImageVector {
    val mime = mimeType?.lowercase() ?: ""
    val ext = extensionOf(name)
    return when {
        mime.startsWith("video") || ext in VIDEO -> Icons.Rounded.PlayArrow
        mime.startsWith("image") || ext in IMAGE -> Icons.Rounded.Image
        mime.startsWith("audio") || ext in AUDIO -> Icons.Rounded.MusicNote
        ext in ARCHIVE -> Icons.Rounded.Archive
        else -> Icons.Rounded.Description
    }
}

private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif")
private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
private val ARCHIVE = setOf("zip", "7z", "tar", "gz", "rar")

private fun isVideo(name: String, mimeType: String?): Boolean =
    mimeType?.lowercase()?.startsWith("video") == true || extensionOf(name) in VIDEO

/** A tile's text block is a fixed height so a two-line name never makes one card taller than its neighbour. */
private val TileTextHeight = 74.dp

/**
 * A file in the grid.
 *
 * The cover is the decrypted frame when one has arrived, and a kind-tinted plate when it has not — never a
 * generic grey box. The extension sits in a glass badge because a glyph cannot tell `.mkv` from `.mp4`, and
 * video also gets a play target in the middle so the whole library is scannable at arm's length.
 */
@Composable
fun FileTile(
    name: String,
    mimeType: String?,
    caption: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    thumbnail: Any? = null,
    compact: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val lift = if (pressed) 0.975f else 1f
    val tint = ArcanePalette.tintFor(mimeType, name)
    val ext = extensionOf(name)
    val video = isVideo(name, mimeType)

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .arcaneCard(shape = RoundedCornerShape(ArcaneRadius.card), selected = picked)
            .semantics {
                contentDescription = "$name, $caption"
                this.selected = picked
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(ArcanePalette.Ink3),
        ) {
            if (thumbnail == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(tint, ArcanePalette.Ink2),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            )
                        )
                )
            }
            if (thumbnail != null) {
                Cover(thumbnail, Modifier.fillMaxSize())
                // A floor for the badge, so a bright photo cannot swallow the extension.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, ArcanePalette.Ink0.copy(alpha = 0.62f))
                            )
                        )
                )
            } else if (!video) {
                Icon(
                    glyphFor(name, mimeType),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.52f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (compact) 28.dp else 38.dp),
                )
            }

            if (video) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(if (compact) 36.dp else 46.dp)
                        .clip(CircleShape)
                        .background(ArcanePalette.Ink0.copy(alpha = 0.46f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.94f),
                        modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                    )
                }
            }

            if (ext.isNotEmpty()) {
                Text(
                    ext.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(if (compact) 4.dp else 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ArcanePalette.Ink0.copy(alpha = 0.58f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (picked) {
                PickDot(
                    picked = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (compact) 4.dp else 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 48.dp else TileTextHeight)
                .padding(horizontal = if (compact) 8.dp else 11.dp, vertical = if (compact) 6.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                name,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A folder in the same grid and the same outer height.
 *
 * It is drawn as a folder rather than a file with a folder icon: a tabbed back and a lighter front flap, so
 * the difference lands before the caption is read and mixed rows of folders and files still line up.
 */
@Composable
fun FolderTile(
    name: String,
    caption: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    compact: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val lift = if (pressed) 0.975f else 1f
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .arcaneCard(shape = RoundedCornerShape(ArcaneRadius.card), selected = picked)
            .semantics {
                contentDescription = "Folder $name, $caption"
                this.selected = picked
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    Brush.linearGradient(
                        listOf(ArcanePalette.Ink3, ArcanePalette.Ink2),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                ),
        ) {
            FolderCover(Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 18.dp else 32.dp))
            if (picked) {
                PickDot(
                    picked = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (compact) 4.dp else 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 48.dp else TileTextHeight)
                .padding(horizontal = if (compact) 8.dp else 11.dp, vertical = if (compact) 6.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                name,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

/** A one-line entry for the list layout, which some people keep on because names are long. */
@Composable
fun EntryRow(
    name: String,
    caption: String,
    glyph: ImageVector,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    trailing: String? = null,
    thumbnail: Any? = null,
    tint: Color = ArcanePalette.tintFor(null, name),
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .arcaneCard(
                shape = RoundedCornerShape(ArcaneRadius.field),
                selected = picked,
            )
            .semantics {
                contentDescription = "$name, $caption"
                this.selected = picked
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(ArcaneRadius.field))
                .background(Brush.linearGradient(listOf(tint, ArcanePalette.Ink2))),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Cover(thumbnail, Modifier.fillMaxSize())
            } else {
                Icon(
                    glyph,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = if (pressed) ArcanePalette.TextHigh else ArcanePalette.TextMedium,
            )
        }
        if (picked) PickDot(picked = true)
    }
}

/**
 * A bitmap appears rather than pops. Simple mode hands Coil a uri and Coil runs its own request; the vault
 * hands over an already-decrypted frame, so the fade is the only placeholder it gets.
 */
@Composable
private fun Cover(model: Any, modifier: Modifier) {
    if (model is Bitmap) {
        val alpha = remember(model) { Animatable(0f) }
        LaunchedEffect(model) { alpha.animateTo(1f, tween(ArcaneMotion.quick)) }
        androidx.compose.foundation.Image(
            bitmap = model.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.graphicsLayer { this.alpha = alpha.value },
            contentScale = ContentScale.Crop,
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

/** The folder silhouette: a dark tabbed back with a lighter front flap and one edge of reflected light. */
@Composable
private fun FolderCover(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = 9.dp.toPx()
        val width = size.width
        val height = size.height
        val back = Path().apply {
            addRoundRect(RoundRect(0f, height * 0.08f, width * 0.48f, height * 0.36f, CornerRadius(radius)))
            addRoundRect(RoundRect(0f, height * 0.24f, width, height, CornerRadius(radius)))
        }
        drawPath(
            back,
            Brush.linearGradient(
                listOf(ArcanePalette.VioletDeep, ArcanePalette.Ink2),
                start = Offset.Zero,
                end = Offset(width, height),
            ),
        )
        val front = Path().apply {
            addRoundRect(RoundRect(0f, height * 0.42f, width, height, CornerRadius(radius)))
        }
        drawPath(
            front,
            Brush.verticalGradient(
                listOf(ArcanePalette.Violet.copy(alpha = 0.90f), ArcanePalette.VioletDeep)
            ),
        )
        drawPath(front, Color.White.copy(alpha = 0.16f), style = Stroke(width = 1.dp.toPx()))
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(radius, height * 0.42f + 1.dp.toPx()),
            end = Offset(width - radius, height * 0.42f + 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** The block of tiles a browser paints before its first listing arrives. */
@Composable
fun TileSkeleton(modifier: Modifier = Modifier, columns: Int = 2) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter),
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tileGap),
    ) {
        repeat(columns) {
            Shimmer(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.68f),
                shape = RoundedCornerShape(ArcaneRadius.card),
            )
        }
    }
}
