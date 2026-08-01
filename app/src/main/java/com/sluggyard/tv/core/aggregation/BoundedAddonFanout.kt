package com.sluggyard.tv.core.aggregation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * A single independently-loadable unit of addon work.
 *
 * [key] is deliberately caller-defined: UI layers use it to update only the row or addon group
 * that completed, without disturbing already-rendered content.
 */
data class AddonFanoutTask<T>(
    val key: String,
    val timeoutMs: Long = DEFAULT_ADDON_TIMEOUT_MS,
    val load: suspend () -> T,
)

/** The terminal outcome for one [AddonFanoutTask]. A failure never cancels sibling work. */
sealed interface AddonFanoutResult<out T> {
    val key: String

    data class Success<T>(
        override val key: String,
        val value: T,
    ) : AddonFanoutResult<T>

    data class Failure(
        override val key: String,
        val cause: Throwable,
    ) : AddonFanoutResult<Nothing>
}

/**
 * Runs independent addon calls with a strict upper bound on in-flight work.
 *
 * Results are emitted as each call finishes, not in declaration order. This is intentional: a
 * consumer can render the first successful catalog or stream group immediately while slow or
 * failed addons remain isolated. Coroutine cancellation is propagated rather than presented as
 * an addon error, so leaving a screen stops unnecessary network work promptly.
 *
 * Each task is bounded by [perTaskTimeoutMs]: a single slow/hanging addon can otherwise pin one
 * of the [maxConcurrent] permits indefinitely, starving faster addons behind it in the queue and
 * making the whole scrape feel slow even though most sources responded quickly. A timed-out task
 * surfaces as an ordinary [AddonFanoutResult.Failure] -- it never cancels sibling work.
 */
fun <T> boundedAddonFanout(
    tasks: List<AddonFanoutTask<T>>,
    maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
    perTaskTimeoutMs: Long = DEFAULT_ADDON_TIMEOUT_MS,
): Flow<AddonFanoutResult<T>> {
    require(maxConcurrent > 0) { "maxConcurrent must be greater than zero" }

    return channelFlow {
        val permits = Semaphore(maxConcurrent)
        tasks.forEach { task ->
            launch {
                permits.withPermit {
                    val timeoutMs = task.timeoutMs.takeIf { it > 0L } ?: perTaskTimeoutMs
                    val result = try {
                        AddonFanoutResult.Success(task.key, withTimeout(timeoutMs) { task.load() })
                    } catch (timedOut: TimeoutCancellationException) {
                        AddonFanoutResult.Failure(task.key, timedOut)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        AddonFanoutResult.Failure(task.key, failure)
                    }
                    send(result)
                }
            }
        }
    }
}

const val DEFAULT_ADDON_CONCURRENCY = 6

/** Generous enough for slow but legitimate addon responses, tight enough that one bad source
 * can't stall the whole fanout past what a user will wait for. */
const val DEFAULT_ADDON_TIMEOUT_MS = 12_000L

/**
 * AIOStreams runs Torz + SeaDex + AnimeTosho then TorBox cache filtering in one request —
 * longer than a single Comet scrape. Keep this under [NetworkClient] callTimeout.
 */
const val AIOSTREAMS_STREAM_TIMEOUT_MS = 20_000L
