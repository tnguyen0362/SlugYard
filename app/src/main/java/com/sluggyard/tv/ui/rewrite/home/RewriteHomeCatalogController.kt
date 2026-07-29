package com.sluggyard.tv.ui.rewrite.home

import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_CONCURRENCY
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import com.sluggyard.tv.core.aggregation.orderedHomeCatalogs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

data class RewriteCatalogRequest(
    val key: HomeCatalogKey,
    val title: String,
    val load: suspend () -> List<RewriteHomePoster>,
)

sealed interface RewriteCatalogLoadState {
    data object Loading : RewriteCatalogLoadState
    data class Content(val posters: List<RewriteHomePoster>) : RewriteCatalogLoadState
    data object Empty : RewriteCatalogLoadState
    data class Error(val cause: Throwable) : RewriteCatalogLoadState
}

data class RewriteCatalogRowState(
    val key: HomeCatalogKey,
    val title: String,
    val loadState: RewriteCatalogLoadState,
)

/** A complete ordered snapshot, emitted whenever a single independent catalog settles. */
data class RewriteHomeCatalogState(
    val rows: List<RewriteCatalogRowState>,
)

/**
 * Replacement Home catalog coordinator.
 *
 * It exposes state snapshots instead of a request-at-a-time callback so Compose can update one
 * row in place. The sequence is stable: the saved order is resolved once, and subsequent network
 * completions only replace that row's state. An error is row-local and never erases a successful
 * sibling catalog.
 */
fun loadRewriteHomeCatalogs(
    requestsInDefaultOrder: List<RewriteCatalogRequest>,
    savedOrder: List<HomeCatalogKey>,
    maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
): Flow<RewriteHomeCatalogState> = flow {
    val byKey = requestsInDefaultOrder.associateBy(RewriteCatalogRequest::key)
    val orderedKeys = pinHomeCatalogDisplayOrder(
        orderedKeys = orderedHomeCatalogs(requestsInDefaultOrder.map(RewriteCatalogRequest::key), savedOrder),
        titleFor = { key -> byKey[key]?.title },
    )
    val rows = orderedKeys.mapNotNull { key ->
        byKey[key]?.let { request ->
            RewriteCatalogRowState(key, request.title, RewriteCatalogLoadState.Loading)
        }
    }.toMutableList()

    emit(RewriteHomeCatalogState(rows.toList()))
    if (rows.isEmpty()) return@flow

    val taskToKey = LinkedHashMap<String, HomeCatalogKey>()
    val tasks = orderedKeys.mapNotNull { key ->
        byKey[key]?.let { request ->
            val taskKey = "${key.addonId}\u0000${key.catalogId}"
            taskToKey[taskKey] = key
            AddonFanoutTask(taskKey, request.load)
        }
    }
    boundedAddonFanout(tasks, maxConcurrent).collect { outcome ->
        val catalogKey = taskToKey[outcome.key] ?: return@collect
        val index = rows.indexOfFirst { it.key == catalogKey }
        if (index < 0) return@collect
        val nextState = when (outcome) {
            is AddonFanoutResult.Success -> {
                if (outcome.value.isEmpty()) RewriteCatalogLoadState.Empty
                else RewriteCatalogLoadState.Content(outcome.value)
            }
            is AddonFanoutResult.Failure -> RewriteCatalogLoadState.Error(outcome.cause)
        }
        rows[index] = rows[index].copy(loadState = nextState)
        emit(RewriteHomeCatalogState(rows.toList()))
    }
}
