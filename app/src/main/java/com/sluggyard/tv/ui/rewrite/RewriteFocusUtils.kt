package com.sluggyard.tv.ui.rewrite

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * A plain `requestFocus()` silently no-ops if the target hasn't been placed by the LazyColumn/
 * layout pass yet -- a fixed `delay()` before calling it is a race, not a fix. Retrying across a
 * few frames lets the request land on whichever frame the target actually becomes focusable.
 */
suspend fun FocusRequester.requestFocusReliably(retries: Int = 5) {
    repeat(retries.coerceAtLeast(1)) { attempt ->
        if (runCatching { requestFocus(); true }.getOrDefault(false)) return
        if (attempt < retries - 1) withFrameNanos { }
    }
}
