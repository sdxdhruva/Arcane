package dev.arcane.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The whole palette, in one file, on purpose. A vault app is read in the dark, so the scale is built from
 * ink (the surfaces) with a single violet accent, and every tint below is a step on that scale rather than
 * a colour chosen per screen.
 *
 * The mid-tones are what carry the design: elevation here is expressed as a lighter surface plus a hairline
 * border, not as a drop shadow, because a shadow on near-black is invisible and a border always reads.
 */
internal object ArcanePalette {

    // Ink — the surface ladder. Background is cooler and darker than surface so content lifts off it.
    val Ink0 = Color(0xFF06080D)
    val Ink1 = Color(0xFF0B0E13)
    val Ink2 = Color(0xFF12161D)
    val Ink3 = Color(0xFF181E28)
    val Ink4 = Color(0xFF222A38)
    val Ink5 = Color(0xFF2E3949)

    // Text. On background never pure white: full white on near-black halates and is tiring to read.
    val TextHigh = Color(0xFFE9ECF3)
    val TextMedium = Color(0xFFA6AEBF)
    val TextFaint = Color(0xFF8590A3)

    val Violet = Color(0xFF9C82FF)
    val VioletDeep = Color(0xFF6C4BD6)
    val VioletDim = Color(0xFF33295C)
    val Cyan = Color(0xFF5FD3E8)
    val Amber = Color(0xFFF2B662)
    val Rose = Color(0xFFF1736B)
    val Mint = Color(0xFF6BD9A6)

    /** Hairlines. Two weights only, so "selected" and "just a border" can never be confused. */
    val Edge = Color(0x14FFFFFF)          // 8%
    val EdgeStrong = Color(0x2BFFFFFF)    // 17%

    val Scrim = Color(0xB3000000)
    val Ripple = Color(0x1FFFFFFF)

    /** The one gradient in the app: a violet wash behind headers, never on a button label. */
    val HeaderWash = Brush.verticalGradient(
        0f to Color(0x26000000),
        0.55f to Color(0x149C82FF),
        1f to Color(0x00000000),
    )

    /** Tile covers get a tint by kind so a library is scannable before any thumbnail decrypts. */
    val VideoTint = Color(0xFF2A2140)
    val ImageTint = Color(0xFF1C2B33)
    val AudioTint = Color(0xFF1E2A22)
    val TextTint = Color(0xFF242632)
    val DocTint = Color(0xFF2C2530)
    val ArchiveTint = Color(0xFF322A22)

    fun tintFor(mimeType: String?, name: String): Color {
        val mime = mimeType?.lowercase() ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video") || ext in VIDEO_EXT -> VideoTint
            mime.startsWith("image") || ext in IMAGE_EXT -> ImageTint
            mime.startsWith("audio") || ext in AUDIO_EXT -> AudioTint
            mime.startsWith("text") || ext in TEXT_EXT -> TextTint
            ext in ARCHIVE_EXT -> ArchiveTint
            else -> DocTint
        }
    }

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif", "bmp")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private val TEXT_EXT = setOf("txt", "md", "log", "json", "xml", "html", "htm", "csv", "kt", "py", "js")
    private val ARCHIVE_EXT = setOf("zip", "7z", "tar", "gz", "rar")
}
