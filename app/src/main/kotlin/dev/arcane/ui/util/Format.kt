package dev.arcane.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The two formatters the browsers need, kept out of the composables so a list of a thousand files does not
 * build a [SimpleDateFormat] per row.
 */
private val dayFormat = SimpleDateFormat("d MMM", Locale.US)
private val dayTimeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.US)
private val yearFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

/** Binary units, because a vault is measured against free space in `/data`, not against a marketing GB. */
fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble()
    var step = 0
    while (amount >= 1024.0 && step < units.lastIndex) {
        amount /= 1024.0
        step++
    }
    val places = if (step == 0 || amount >= 100) 0 else 1
    val text = String.format(Locale.US, "%.${places}f", amount)
    return "$text ${units[step]}"
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** A file's own date, phrased the way a file manager phrases it: time today, day this year, year before. */
fun formatModified(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return "—"
    val then = Date(millis)
    val sameYear = TimeUnit.MILLISECONDS.toDays(now - millis) < 365 &&
        (now - millis) >= -TimeUnit.DAYS.toMillis(1)
    return when {
        (now - millis) < TimeUnit.DAYS.toMillis(1) && millis <= now -> dayTimeFormat.format(then)
        sameYear -> dayFormat.format(then)
        else -> yearFormat.format(then)
    }
}

/** An extension is a better glyph than a mime type for a file the user named themselves. */
fun extensionOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return if (ext == name.lowercase(Locale.US) || ext.length > 5) "" else ext
}
