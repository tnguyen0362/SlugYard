package com.sluggyard.tv.ui.rewrite.player

import android.text.format.DateFormat
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Small TV-focus and date utilities owned by the rewritten player presentation. */
suspend fun FocusRequester.requestRewritePlayerFocus(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) { withFrameNanos { } }
    repeat(4) { attempt ->
        if (runCatching { requestFocus(); true }.getOrDefault(false)) return
        if (attempt < 3) withFrameNanos { }
    }
}

fun formatRewritePlayerReleaseDate(isoDate: String): String {
    val locale = Locale.getDefault()
    val output = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale)
    val utc = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(isoDate)
    }.getOrNull()
    val dateOnly = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate) }.getOrNull()
    return (utc ?: dateOnly)?.let(output::format).orEmpty()
}
