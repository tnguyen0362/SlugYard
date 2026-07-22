package com.sluggyard.tv.ui.screens.player

import android.graphics.Rect
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.media3.ui.PlayerView
import com.sluggyard.tv.R

enum class AspectMode(@StringRes val labelResId: Int) {
    ORIGINAL(R.string.player_aspect_fit),
    FULL_SCREEN(R.string.player_aspect_crop),
    STRETCH(R.string.player_aspect_stretch),
    SLIGHT_ZOOM(R.string.player_aspect_mode_slight_zoom),
    CINEMA_ZOOM(R.string.player_aspect_mode_cinema_zoom),
    VERTICAL_STRETCH(R.string.player_aspect_fit_height),
    HORIZONTAL_STRETCH(R.string.player_aspect_fit_width)
}

internal fun nextAspectMode(current: AspectMode): AspectMode {
    val modes = AspectMode.entries
    return modes[(modes.indexOf(current) + 1) % modes.size]
}

internal fun aspectModeLabel(mode: AspectMode, getString: (Int) -> String): String =
    getString(mode.labelResId)

internal data class AspectScale(val scaleX: Float, val scaleY: Float)

internal fun aspectModeNeedsVideoAspect(mode: AspectMode): Boolean = when (mode) {
    AspectMode.FULL_SCREEN, AspectMode.STRETCH,
    AspectMode.VERTICAL_STRETCH, AspectMode.HORIZONTAL_STRETCH -> true
    AspectMode.ORIGINAL, AspectMode.SLIGHT_ZOOM, AspectMode.CINEMA_ZOOM -> false
}

internal fun readViewAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f

internal fun readExoVideoAspectRatio(playerView: PlayerView): Float? {
    val size = playerView.player?.videoSize ?: return null
    if (size.height <= 0) return null
    return (size.width.toFloat() * size.pixelWidthHeightRatio) / size.height.toFloat()
}

internal fun resolveAspectScale(mode: AspectMode, viewAspect: Float, videoAspect: Float?): AspectScale {
    if (viewAspect <= 0f) return AspectScale(1f, 1f)
    val safeVideo = videoAspect?.takeIf { it > 0f }

    return when (mode) {
        AspectMode.ORIGINAL -> AspectScale(1f, 1f)

        AspectMode.FULL_SCREEN -> {
            val v = safeVideo ?: return AspectScale(1f, 1f)
            val uniform = if (v > viewAspect) v / viewAspect else viewAspect / v
            AspectScale(uniform, uniform)
        }

        AspectMode.STRETCH -> {
            val v = safeVideo ?: return AspectScale(1f, 1f)
            if (v > viewAspect) AspectScale(1f, v / viewAspect)
            else AspectScale(viewAspect / v, 1f)
        }

        AspectMode.SLIGHT_ZOOM -> AspectScale(1.15f, 1.15f)
        AspectMode.CINEMA_ZOOM -> AspectScale(1.33f, 1.33f)

        AspectMode.VERTICAL_STRETCH -> {
            val v = safeVideo ?: return AspectScale(1f, 1f)
            if (v > viewAspect) {
                val uniform = v / viewAspect
                AspectScale(uniform, uniform)
            } else AspectScale(1f, 1f)
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            val v = safeVideo ?: return AspectScale(1f, 1f)
            if (v < viewAspect) {
                val uniform = viewAspect / v
                AspectScale(uniform, uniform)
            } else AspectScale(1f, 1f)
        }
    }
}

internal fun applyExoAspectMode(playerView: PlayerView, mode: AspectMode) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = findVideoSurfaceView(playerView)
    val target = contentFrame ?: surfaceView ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)

    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    applyAspectScale(target, mode, viewAspect, videoAspect)
    centerTargetInPlayer(playerView, target)
}

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    val target = findVideoSurfaceView(playerView) ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)
    resetAspectTransform(playerView)
    applyAspectScale(target, mode, viewAspect, videoAspect)
}

internal fun addExoAspectLayoutChangeListener(
    playerView: PlayerView,
    listener: View.OnLayoutChangeListener
): () -> Unit {
    val targets = linkedSetOf<View>()
    targets.add(playerView)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(targets::add)
    findVideoSurfaceView(playerView)?.let(targets::add)
    targets.forEach { it.addOnLayoutChangeListener(listener) }
    return { targets.forEach { it.removeOnLayoutChangeListener(listener) } }
}

private fun applyAspectScale(target: View, mode: AspectMode, viewAspect: Float, videoAspect: Float?) {
    val scale = resolveAspectScale(mode, viewAspect, videoAspect)
    target.scaleX = scale.scaleX
    target.scaleY = scale.scaleY
}

private fun resetAspectTransform(view: View) {
    view.scaleX = 1f
    view.scaleY = 1f
    view.translationX = 0f
    view.translationY = 0f
    if (view.width > 0) view.pivotX = view.width / 2f
    if (view.height > 0) view.pivotY = view.height / 2f
}

private fun centerTargetInPlayer(playerView: PlayerView, target: View) {
    if (target === playerView || playerView.width <= 0 || playerView.height <= 0 ||
        target.width <= 0 || target.height <= 0
    ) return

    val rect = Rect(0, 0, target.width, target.height)
    playerView.offsetDescendantRectToMyCoords(target, rect)
    target.translationX = playerView.width / 2f - (rect.left + rect.width() / 2f)
    target.translationY = playerView.height / 2f - (rect.top + rect.height() / 2f)
}

private fun findVideoSurfaceView(view: View): View? = when (view) {
    is SurfaceView, is TextureView -> view
    is ViewGroup -> {
        for (i in 0 until view.childCount) {
            findVideoSurfaceView(view.getChildAt(i))?.let { return it }
        }
        null
    }
    else -> null
}