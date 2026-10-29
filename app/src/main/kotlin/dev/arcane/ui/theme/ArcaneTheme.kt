package dev.arcane.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import kotlin.math.hypot

private val DarkScheme = darkColorScheme(
    primary = ArcanePalette.Violet,
    onPrimary = Color(0xFF150E2A),
    primaryContainer = ArcanePalette.VioletDim,
    onPrimaryContainer = Color(0xFFE4DBFF),
    secondary = ArcanePalette.Cyan,
    onSecondary = Color(0xFF062027),
    tertiary = ArcanePalette.Mint,
    onTertiary = Color(0xFF07231A),
    error = ArcanePalette.Rose,
    onError = Color(0xFF2B0A09),
    errorContainer = Color(0xFF3B1512),
    background = ArcanePalette.Ink1,
    onBackground = ArcanePalette.TextHigh,
    surface = ArcanePalette.Ink2,
    onSurface = ArcanePalette.TextHigh,
    surfaceVariant = ArcanePalette.Ink3,
    onSurfaceVariant = ArcanePalette.TextMedium,
    surfaceContainerHighest = ArcanePalette.Ink4,
    outline = ArcanePalette.TextFaint,
    outlineVariant = ArcanePalette.Edge,
    scrim = ArcanePalette.Scrim,
    inverseSurface = ArcanePalette.Ink4,
    inverseOnSurface = ArcanePalette.Ink1,
)

/**
 * Dark, always, whatever the system says.
 *
 * Not a stylistic stubbornness: the palette every screen draws with is the ink ladder in [ArcanePalette], and
 * a light Material scheme would give white surfaces carrying near-white text. Beyond that a vault app is used
 * in low light, and a dark surface makes the plaintext on screen less legible to anyone reading over a
 * shoulder — which is the same reason the window blocks screenshots.
 */
@Composable
fun ArcaneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = ArcaneType.Scale,
        shapes = ArcaneRadius.Shapes,
        content = content,
    )
}

/**
 * A page background: flat ink with one faint radial wash anchored off the top edge, so the app has a room to
 * be in rather than a colour.
 *
 * Two rules keep it from becoming a seam. The radius is the screen's own diagonal, so the wash reaches every
 * corner instead of stopping in the middle of the page — a wash that ends on-screen reads as a half-painted
 * background. And it fades to *transparent violet*, not to `Color.Transparent`: interpolating toward zero
 * black instead of zero alpha drags a grey band in with it, which is the other way a gradient can look cut.
 */
@Composable
fun Modifier.arcaneBackdrop(): Modifier = drawWithCache {
    val base = Brush.verticalGradient(listOf(ArcanePalette.Ink1, ArcanePalette.Ink0))
    val centreX = size.width * 0.74f
    val centreY = -size.height * 0.14f
    val radius = maxOf(
        hypot(centreX, centreY),
        hypot(size.width - centreX, centreY),
        hypot(centreX, size.height - centreY),
        hypot(size.width - centreX, size.height - centreY),
    )
    val wash = Brush.radialGradient(
        // Bunched at the start and spread out at the end, so the falloff decelerates instead of tapering to a
        // visible line where the last stop lands.
        0f to Color(0x2B9C82FF),
        0.22f to Color(0x1A9C82FF),
        0.44f to Color(0x0E9C82FF),
        0.72f to Color(0x069C82FF),
        1f to Color(0x009C82FF),
        center = Offset(centreX, centreY),
        radius = radius,
    )
    onDrawBehind {
        drawRect(base)
        drawRect(wash)
    }
}

/**
 * Tonal elevation, the substitute for shadows on near-black: one step up the ink ladder plus a hairline
 * border. Selected adds the accent stroke and a diagonal wash, so a picked tile is legible in greyscale.
 */
@Composable
fun Modifier.arcaneCard(
    shape: Shape = MaterialTheme.shapes.medium,
    raised: Boolean = false,
    selected: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    val fill = if (raised) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val edge = if (selected) accent else if (raised) ArcanePalette.EdgeStrong else ArcanePalette.Edge
    val tint = if (selected) {
        Modifier.drawWithCache {
            val brush = Brush.linearGradient(
                colors = listOf(Color(0x1FFFFFFF), Color(0x00FFFFFF)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            onDrawBehind { drawRect(brush) }
        }
    } else {
        Modifier
    }
    return this
        .clip(shape)
        .background(fill)
        .then(tint)
        .border(hairline(), edge, shape)
}

/** The pill every chip and toggle in the app is drawn from. */
val ArcanePill: Shape = RoundedCornerShape(percent = 50)
