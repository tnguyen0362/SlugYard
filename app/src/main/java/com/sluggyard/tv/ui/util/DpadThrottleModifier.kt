package com.sluggyard.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import kotlinx.coroutines.launch

val LocalFastHorizontalNavigationEnabled = compositionLocalOf { false }

/**
 * Holds the up/down [FocusRequester] neighbors declared by whichever item is *currently
 * focused* within a [dpadRepeatThrottle]-wrapped container. Items update this (via
 * `onFocusChanged`) as focus moves between them.
 *
 * Why this exists: [dpadRepeatThrottle] sits on an ancestor (e.g. the Home screen's
 * LazyColumn) and intercepts DPAD key events via `onPreviewKeyEvent`, which fires on
 * ancestors *before* it reaches the currently-focused descendant (preview events travel
 * root -> leaf). That means whenever this modifier decided to handle a key press itself
 * (previously: every repeat-rate-gated event), it was calling Compose's default
 * `focusManager.moveFocus(direction)` -- a geometric 2D search that only considers
 * currently-composed nodes -- and this ran *instead of*, not *in addition to*, any
 * explicit neighbor-based routing a descendant had wired up for itself (e.g.
 * RewritePosterCard's row-to-row FocusRequesters in RewriteHomeScreen.kt). Since
 * LazyColumn/LazyRow decompose off-screen items, the default search would walk straight
 * past scrolled-out rows to whatever was still composed and geometrically closest --
 * which was the hero panel. Registering neighbors here lets the ancestor prefer the
 * descendant's explicit routing instead of blindly overriding it.
 */
class DpadVerticalNeighbors {
    var up: FocusRequester? = null
    var down: FocusRequester? = null
    // Focus alone doesn't guarantee the LazyColumn scrolls the target into view -- a target
    // that's only barely composed (e.g. just entering the LazyColumn's prefetch window) can
    // accept requestFocus() without the list's scroll position changing, leaving the
    // "focused" item still off-screen. Whichever item registers up/down also registers how to
    // explicitly scroll its neighbor into view; dpadRepeatThrottle runs this before requesting
    // focus so the target is both visible and focusable by the time focus lands.
    var upBringIntoView: (suspend () -> Unit)? = null
    var downBringIntoView: (suspend () -> Unit)? = null
}

val LocalDpadVerticalNeighbors = compositionLocalOf { DpadVerticalNeighbors() }

/**
 * Throttles D-pad key repeats to prevent HWUI overload and focus jank
 * when a directional key is held down.  Consumes rapid repeats and
 * manually moves focus at a controlled rate.
 *
 * @param horizontalGateMs minimum interval between horizontal repeats
 * @param verticalGateMs   minimum interval between vertical repeats
 */
fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = 80L,
    verticalGateMs: Long = 112L
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val fastHorizontalNavigationEnabled = LocalFastHorizontalNavigationEnabled.current
    val dpadVerticalNeighbors = LocalDpadVerticalNeighbors.current
    val lastRepeatTime = remember { longArrayOf(0L) }
    val scope = rememberCoroutineScope()

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || native.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val isHorizontal = native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!isVertical && !isHorizontal) return@onPreviewKeyEvent false

        // Gate held-button repeats at a controlled rate; the very first press of a tap
        // (repeatCount == 0) always falls through immediately below so single presses feel
        // instant and are never dropped.
        if (native.repeatCount > 0) {
            val gateMs = if (isVertical) {
                verticalGateMs
            } else if (fastHorizontalNavigationEnabled) {
                48L
            } else {
                horizontalGateMs
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatTime[0] < gateMs) {
                return@onPreviewKeyEvent true
            }
            lastRepeatTime[0] = now
        }

        if (isVertical) {
            // Prefer the currently-focused item's explicit neighbor over Compose's default
            // geometric focus search -- see the class doc on DpadVerticalNeighbors above.
            val isUp = native.keyCode == KeyEvent.KEYCODE_DPAD_UP
            val neighbor = if (isUp) dpadVerticalNeighbors.up else dpadVerticalNeighbors.down
            val bringIntoView = if (isUp) dpadVerticalNeighbors.upBringIntoView else dpadVerticalNeighbors.downBringIntoView
            if (neighbor != null) {
                // Explicitly scroll the target into view first -- requestFocus() alone doesn't
                // guarantee the LazyColumn's scroll position follows, especially for a target
                // that just entered the prefetch window and isn't fully visible yet. Then a
                // plain requestFocus() can silently no-op (throws, swallowed) if the target
                // hasn't been placed by the LazyColumn on this frame; requestFocusReliably()
                // retries across a few frames instead of dropping the DPAD press with no visible
                // effect. See RewriteFocusUtils.kt.
                scope.launch {
                    bringIntoView?.invoke()
                    runCatching { neighbor.requestFocusReliably() }
                    // A target that just scrolled into place can accept focus and then silently
                    // drop it a couple hundred milliseconds later (observed on-device: a fresh
                    // LazyColumn/LazyRow item's focus-triggered scale/elevation animation
                    // settling appears to invalidate its focus target right after landing).
                    // Re-assert repeatedly over ~1s so the request eventually wins against
                    // whatever transient recomposition is reverting it, rather than leaving the
                    // user stranded back on whatever had focus before.
                    repeat(12) {
                        kotlinx.coroutines.delay(80)
                        runCatching { neighbor.requestFocus() }
                    }
                }
                return@onPreviewKeyEvent true
            }
            if (native.repeatCount == 0) return@onPreviewKeyEvent false
            val direction = if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down
            focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        } else {
            // No explicit horizontal neighbor concept exists (LazyRow's own default search
            // handles that fine within a single row); only intervene on gated repeats.
            if (native.repeatCount == 0) return@onPreviewKeyEvent false
            val direction = if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) FocusDirection.Left else FocusDirection.Right
            focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
    }
}
