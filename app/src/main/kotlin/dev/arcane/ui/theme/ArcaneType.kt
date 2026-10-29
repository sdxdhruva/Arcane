package dev.arcane.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One type scale, tuned rather than inherited.
 *
 * Sizes step by 2sp, letter-spacing tightens as size grows (loose tracking on a large word looks amateur),
 * and body copy stays at 15–16sp because below that a filename list is unreadable. Weights 400/500/600 do
 * all the work — 700 is reserved for the four words that are ever a title.
 */
internal object ArcaneType {

    // Readable body first, everything else derives from it.
    val body = 15.sp

    val Scale = Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.7).sp,
        ),
        displaySmall = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 29.sp,
            letterSpacing = (-0.35).sp,
        ),
        headlineSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.1).sp,
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        bodySmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.1.sp,
        ),
        labelLarge = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.2.sp,
        ),
        labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
        ),
        // Eyebrows above a section. Uppercase is the only place tracking goes wide on purpose.
        labelSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 1.1.sp,
        ),
    )

    /** Mono for the byte/hex panes; serif exists for nothing else in the app, so it is declared where used. */
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    val Reader = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 17.sp,
        lineHeight = 27.sp,
    )
}
