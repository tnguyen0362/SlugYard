package com.sluggyard.tv.ui.app.home

import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_CONCURRENCY
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import com.sluggyard.tv.core.aggregation.orderedHomeCatalogs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

data class CatalogRequest(
    val key: HomeCatalogKey,
    val title: String,
    val load: suspend () -> List<HomePoster>,
)

sealed interface CatalogLoadState {
    data object Loading : CatalogLoadState
    data class Content(val posters: List<HomePoster>) : CatalogLoadState
    data object Empty : CatalogLoadState
    data class Error(val cause: Throwable) : CatalogLoadState
}

data class CatalogRowState(
    val key: HomeCatalogKey,
    val title: String,
    val loadState: CatalogLoadState,
)

/** A complete ordered snapshot, emitted whenever a single independent catalog settles. */
data class HomeCatalogState(
    val rows: List<CatalogRowState>,
)

/**
 * Replacement Home catalog coordinator.
 *
 * It exposes state snapshots instead of a request-at-a-time callback so Compose can update one
 * row in place. The sequence is stable: the saved order is resolved once, and subsequent network
 * completions only replace that row's state. An error is row-local and never erases a successful
 * sibling catalog.
 *
 * **Two-wave load:** Popular/Featured (Cinemeta + Metahub) finish first so their heavy art can
 * warm before TMDB genre rails compete for the same concurrency budget. Genre shelves lag a
 * little on purpose — first shelves matter most on TV cold start / fast D-pad.
 */
fun loadHomeCatalogs(
    requestsInDefaultOrder: List<CatalogRequest>,
    savedOrder: List<HomeCatalogKey>,
    maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
): Flow<HomeCatalogState> = flow {
    val byKey = requestsInDefaultOrder.associateBy(CatalogRequest::key)
    val orderedKeys = pinHomeCatalogDisplayOrder(
        orderedKeys = orderedHomeCatalogs(requestsInDefaultOrder.map(CatalogRequest::key), savedOrder),
        titleFor = { key -> byKey[key]?.title },
    )
    val rows = orderedKeys.mapNotNull { key ->
        byKey[key]?.let { request ->
            CatalogRowState(key, request.title, CatalogLoadState.Loading)
        }
    }.toMutableList()

    emit(HomeCatalogState(rows.toList()))
    if (rows.isEmpty()) return@flow

    fun tasksFor(keys: List<HomeCatalogKey>): Pair<List<AddonFanoutTask<List<HomePoster>>>, Map<String, HomeCatalogKey>> {
        val taskToKey = LinkedHashMap<String, HomeCatalogKey>()
        val tasks = keys.mapNotNull { key ->
            byKey[key]?.let { request ->
                val taskKey = "${key.addonId}\u0000${key.catalogId}"
                taskToKey[taskKey] = key
                AddonFanoutTask(taskKey, load = request.load)
            }
        }
        return tasks to taskToKey
    }

    suspend fun collectWave(
        keys: List<HomeCatalogKey>,
        concurrency: Int,
    ) {
        if (keys.isEmpty()) return
        val (tasks, taskToKey) = tasksFor(keys)
        if (tasks.isEmpty()) return
        boundedAddonFanout(tasks, concurrency.coerceAtLeast(1)).collect { outcome ->
            val catalogKey = taskToKey[outcome.key] ?: return@collect
            val index = rows.indexOfFirst { it.key == catalogKey }
            if (index < 0) return@collect
            val nextState = when (outcome) {
                is AddonFanoutResult.Success -> {
                    if (outcome.value.isEmpty()) CatalogLoadState.Empty
                    else CatalogLoadState.Content(outcome.value)
                }
                is AddonFanoutResult.Failure -> CatalogLoadState.Error(outcome.cause)
            }
            rows[index] = rows[index].copy(loadState = nextState)
            emit(HomeCatalogState(rows.toList()))
        }
    }

    val priorityKeys = orderedKeys.filter { key ->
        isPrimaryHomeShelfTitle(byKey[key]?.title.orEmpty())
    }
    val restKeys = orderedKeys.filter { key -> key !in priorityKeys.toSet() }

    // Wave 1: only Popular/Featured (or empty when those titles are absent).
    collectWave(
        keys = priorityKeys,
        concurrency = priorityKeys.size.coerceAtMost(maxConcurrent).coerceAtLeast(1),
    )
    // Wave 2: genre / TMDB / remaining addon catalogs.
    collectWave(keys = restKeys, concurrency = maxConcurrent)
}

/** Cinemeta rails with heavy Metahub backdrops — load and warm before TMDB genre shelves. */
fun isPrimaryHomeShelfTitle(title: String): Boolean {
    val t = title.lowercase()
    return "popular" in t || "featured" in t
}

