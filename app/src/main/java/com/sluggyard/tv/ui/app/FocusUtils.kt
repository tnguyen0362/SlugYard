package com.sluggyard.tv.ui.app

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * A plain `requestFocus()` silently no-ops if the target hasn't been placed by the LazyColumn/
 * layout pass yet -- a fixed `delay()` before calling it is a race, not a fix. Retrying across a
 * few frames lets the request land on whichever frame the target actually becomes focusable.
 */
suspend fun FocusRequester.requestFocusReliably(retries: Int = 5): Boolean {
    repeat(retries.coerceAtLeast(1)) { attempt ->
        val focused = runCatching { requestFocus() }.getOrDefault(false)
        if (focused) return true
        if (attempt < retries - 1) withFrameNanos { }
    }
    return false
}

/**
 * Declare explicit vertical focus exits for TV shelves.
 *
 * LazyRow index 0 is especially prone to broken geometric Up/Down search; every poster index must
 * own the same row-level [up]/[down] targets (or [FocusRequester.Cancel] when a key handler owns
 * the move) rather than only wiring the first card.
 */
fun Modifier.verticalFocusExits(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
): Modifier = focusProperties {
    this.up = up ?: FocusRequester.Cancel
    this.down = down ?: FocusRequester.Cancel
}
