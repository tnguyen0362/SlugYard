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

    val taskToKey = LinkedHashMap<String, HomeCatalogKey>()
    val tasks = orderedKeys.mapNotNull { key ->
        byKey[key]?.let { request ->
            val taskKey = "${key.addonId}\u0000${key.catalogId}"
            taskToKey[taskKey] = key
            AddonFanoutTask(taskKey, load = request.load)
        }
    }
    boundedAddonFanout(tasks, maxConcurrent).collect { outcome ->
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
