package com.sluggyard.tv.core.aggregation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A single independently-loadable unit of addon work.
 *
 * [key] is deliberately caller-defined: UI layers use it to update only the row or addon group
 * that completed, without disturbing already-rendered content.
 */
data class AddonFanoutTask<T>(
    val key: String,
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
 */
fun <T> boundedAddonFanout(
    tasks: List<AddonFanoutTask<T>>,
    maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
): Flow<AddonFanoutResult<T>> {
    require(maxConcurrent > 0) { "maxConcurrent must be greater than zero" }

    return channelFlow {
        val permits = Semaphore(maxConcurrent)
        tasks.forEach { task ->
            launch {
                permits.withPermit {
                    val result = try {
                        AddonFanoutResult.Success(task.key, task.load())
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
