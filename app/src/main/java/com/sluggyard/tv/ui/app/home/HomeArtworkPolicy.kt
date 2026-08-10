package com.sluggyard.tv.ui.app.home

import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val HomePosterWidthDp = 176f
private const val HomePosterHeightDp = 264f
private const val HomeLandscapeAspect = 16f / 9f

internal data class HomeArtworkPixelSize(
    val width: Int,
    val height: Int,
) {
    fun toCoilSize(): Size = Size(width, height)
}

internal fun homePortraitArtworkSize(
    density: Float,
    widthDp: Float = HomePosterWidthDp,
    heightDp: Float = HomePosterHeightDp,
): HomeArtworkPixelSize = HomeArtworkPixelSize(
    width = (widthDp * density).roundToInt().coerceAtLeast(1),
    height = (heightDp * density).roundToInt().coerceAtLeast(1),
)

internal fun homeLandscapeArtworkSize(
    density: Float,
    heightDp: Float = HomePosterHeightDp,
): HomeArtworkPixelSize {
    val targetWidth = (heightDp * HomeLandscapeAspect * density).roundToInt().coerceAtLeast(1)
    val targetHeight = (heightDp * density).roundToInt().coerceAtLeast(1)
    return HomeArtworkPixelSize(
        width = targetWidth,
        height = targetHeight,
    )
}

internal fun homePosterMemoryCacheKey(
    posterId: String,
    url: String,
    size: HomeArtworkPixelSize,
): String = "poster:$posterId:$url:${size.width}x${size.height}"

internal fun homeBackdropMemoryCacheKey(
    posterId: String,
    url: String,
    size: HomeArtworkPixelSize,
): String = "card-backdrop:$posterId:$url:${size.width}x${size.height}"

internal fun homePosterFallbackMemoryCacheKey(
    posterId: String,
    url: String,
    size: HomeArtworkPixelSize,
): String = "poster-fallback:$posterId:$url:${size.width}x${size.height}"

internal fun homeRowSupportsLandscape(rowId: String): Boolean = rowId != "continue_watching"

internal fun remainingHomeSplashBudgetMs(
    startedAtElapsedMs: Long,
    nowElapsedMs: Long,
    maximumDurationMs: Long,
): Long = (maximumDurationMs - (nowElapsedMs - startedAtElapsedMs)).coerceAtLeast(0L)

internal suspend fun <T> withRemainingHomeSplashBudget(
    startedAtElapsedMs: Long,
    nowElapsedMs: Long,
    maximumDurationMs: Long,
    block: suspend CoroutineScope.() -> T,
): T? {
    val remaining = remainingHomeSplashBudgetMs(
        startedAtElapsedMs = startedAtElapsedMs,
        nowElapsedMs = nowElapsedMs,
        maximumDurationMs = maximumDurationMs,
    )
    if (remaining <= 0L) return null
    return withTimeoutOrNull(remaining, block)
}

internal suspend fun <T> runHomeArtworkWarmBatch(
    targets: List<T>,
    request: suspend (T) -> Boolean,
): Int = coroutineScope {
    targets.map { target ->
        async {
            try {
                request(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
        }
    }.awaitAll().count { it }
}
