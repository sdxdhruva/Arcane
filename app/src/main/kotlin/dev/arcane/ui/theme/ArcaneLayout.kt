package dev.arcane.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One radius scale. Four steps, and no screen is allowed to invent a fifth. */
internal object ArcaneRadius {
    val chip = 8.dp
    val field = 14.dp
    val card = 20.dp
    val sheet = 28.dp

    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(chip),
        small = RoundedCornerShape(field),
        medium = RoundedCornerShape(card),
        large = RoundedCornerShape(sheet),
        extraLarge = RoundedCornerShape(sheet),
    )
}

/**
 * A 4pt rhythm with named steps. The two that matter are [gutter] (screen edges) and [flow] (between
 * stacked content); anything else is a deviation someone should be able to point at.
 */
internal object ArcaneSpace {
    val hair = 2.dp
    val tight = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val gutter: Dp = 16.dp
    val md = 20.dp
    val flow: Dp = 24.dp
    val lg = 32.dp
    val xl = 48.dp

    /** Tiles are the one grid that repeats, so its gap is a constant rather than a call-site argument. */
    val tileGap = 10.dp
}

/**
 * Motion lives here so a screen cannot quietly become slower than the last one. 220ms for anything local to
 * a control, 340ms for anything that changes the screen, springs for state that should feel physical.
 */
internal object ArcaneMotion {
    const val quick = 220
    const val slow = 340

    fun <T> springy() = spring<T>(dampingRatio = 0.78f, stiffness = 380f)

    /**
     * 0 when the owner has turned animation scales off in developer options or an accessibility setting.
     * Callers multiply their duration by it rather than branching on it, so reduced motion is the same code
     * path arriving instantly instead of a second, untested version of every screen.
     */
    @Composable
    fun durationScale(): Float {
        val context = LocalContext.current
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return if (scale <= 0f) 0f else 1f
    }
}

/**
 * The standard press response: every tappable surface in the app goes through this, so tiles, chips and
 * cards all compress by the same amount on the same curve.
 */
@Composable
fun Modifier.pressed(interaction: Float): Modifier {
    val shrink = 1f - 0.035f * interaction.coerceIn(0f, 1f)
    val scale by animateFloatAsState(
        targetValue = shrink,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** One physical pixel, whatever the density claims. */
@Composable
internal fun hairline(): Dp = with(LocalDensity.current) { (1f / density).toDp() }
