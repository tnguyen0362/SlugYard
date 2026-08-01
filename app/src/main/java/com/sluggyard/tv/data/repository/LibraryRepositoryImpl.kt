package com.sluggyard.tv.data.repository

import android.util.Log
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.data.local.LibraryPreferences
import com.sluggyard.tv.data.local.TraktAuthDataStore
import com.sluggyard.tv.data.local.TraktSettingsDataStore
import com.sluggyard.tv.domain.repository.MetaRepository
import com.sluggyard.tv.domain.model.LibraryEntry
import com.sluggyard.tv.domain.model.LibraryEntryInput
import com.sluggyard.tv.domain.model.LibraryListTab
import com.sluggyard.tv.domain.model.LibrarySourceMode
import com.sluggyard.tv.domain.model.ListMembershipChanges
import com.sluggyard.tv.domain.model.ListMembershipSnapshot
import com.sluggyard.tv.domain.model.SavedLibraryItem
import com.sluggyard.tv.domain.model.TraktListPrivacy
import com.sluggyard.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified library facade: routes between a local-only library (persisted via
 * [LibraryPreferences]) and a Trakt-backed library (delegated to
 * [TraktLibraryService]) based on the configured [LibrarySourceMode] and the
 * current Trakt auth state.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val libraryPreferences: LibraryPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktLibraryService: TraktLibraryService,
    private val metaRepository: MetaRepository,
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedLogoIds = mutableSetOf<String>()

    override val sourceMode: Flow<LibrarySourceMode> = combine(
        traktSettingsDataStore.librarySourceMode,
        traktAuthDataStore.isEffectivelyAuthenticated
    ) { mode, isTraktAuthenticated ->
        if (mode == LibrarySourceMode.TRAKT && !isTraktAuthenticated) LibrarySourceMode.LOCAL else mode
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = sourceMode
        .flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) traktLibraryService.observeIsRefreshing() else flowOf(false)
        }
        .distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> = sourceMode
        .flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeAllItems()
            } else {
                libraryPreferences.libraryItems.map { items ->
                    items.map { saved ->
                        LibraryEntry(
                            id = saved.id,
                            type = saved.type,
                            name = saved.name,
                            poster = saved.poster,
                            posterShape = saved.posterShape,
                            background = saved.background,
                            logo = saved.logo,
                            description = saved.description,
                            releaseInfo = saved.releaseInfo,
                            imdbRating = saved.imdbRating,
                            genres = saved.genres,
                            addonBaseUrl = saved.addonBaseUrl,
                            listedAt = saved.addedAt
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = traktAuthDataStore.isEffectivelyAuthenticated
        .flatMapLatest { isAuthenticated ->
            if (isAuthenticated) traktLibraryService.observeListTabs() else flowOf(emptyList())
        }
        .distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> =
        sourceMode.flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeMembership(itemId, itemType).map { it.isNotEmpty() }
            } else {
                libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            }
        }.distinctUntilChanged()

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> =
        sourceMode.flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeMembership(itemId, itemType)
                    .map { it.contains(TraktLibraryService.WATCHLIST_KEY) }
            } else {
                libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            }
        }.distinctUntilChanged()

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        val currentMode = traktSettingsDataStore.librarySourceMode.first()
        val isTraktAuth = traktAuthDataStore.isEffectivelyAuthenticated.first()

        if (currentMode == LibrarySourceMode.TRAKT && isTraktAuth) {
            traktLibraryService.toggleWatchlist(item)
            return
        }

        val isInLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        if (isInLocal) {
            libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
        } else {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val isTraktAuth = traktAuthDataStore.isEffectivelyAuthenticated.first()
        val inLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()

        val membership = mutableMapOf<String, Boolean>()
        membership[LOCAL_LIST_KEY] = inLocal

        if (isTraktAuth) {
            val traktSnapshot = traktLibraryService.getMembershipSnapshot(item)
            membership.putAll(traktSnapshot.listMembership)
        }

        return ListMembershipSnapshot(listMembership = membership)
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        val isTraktAuth = traktAuthDataStore.isEffectivelyAuthenticated.first()
        val desired = changes.desiredMembership

        val localDesired = desired[LOCAL_LIST_KEY] == true
        val currentlyInLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        if (localDesired != currentlyInLocal) {
            if (localDesired) {
                libraryPreferences.addItem(item.toSavedLibraryItem())
            } else {
                libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
            }
        }

        if (isTraktAuth) {
            val traktChanges = desired.filterKeys { it != LOCAL_LIST_KEY }
            if (traktChanges.isNotEmpty()) {
                traktLibraryService.applyMembershipChanges(
                    item,
                    ListMembershipChanges(desiredMembership = traktChanges)
                )
            }
        }
    }

    override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {
        requireTraktAuth()
        traktLibraryService.createPersonalList(name = name, description = description, privacy = privacy)
    }

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        requireTraktAuth()
        traktLibraryService.updatePersonalList(listId = listId, name = name, description = description, privacy = privacy)
    }

    override suspend fun deletePersonalList(listId: String) {
        requireTraktAuth()
        traktLibraryService.deletePersonalList(listId)
    }

    override suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        requireTraktAuth()
        traktLibraryService.reorderPersonalLists(orderedListIds)
    }

    override suspend fun refreshNow() {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.refreshNow()
        }
    }

    private suspend fun requireTraktAuth() {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            throw IllegalStateException(appContext.getString(com.sluggyard.tv.R.string.trakt_error_auth_required))
        }
    }

    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
        id = itemId,
        type = itemType,
        name = title,
        poster = poster,
        posterShape = posterShape,
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        logo = logo
    )

    private suspend fun hydrateLibraryLogos(items: List<LibraryEntry>) {
        items.take(20).forEach { entry ->
            hydratedLogoIds.add(entry.id)
            runCatching {
                val result = metaRepository.getMetaFromPrimaryAddon(entry.type, entry.id)
                    .firstOrNull { it is NetworkResult.Success }
                val logo = (result as? NetworkResult.Success)?.data?.logo
                if (logo != null) {
                    libraryPreferences.updateLogo(entry.id, entry.type, logo)
                }
            }.onFailure { Log.w("LibraryRepo", "Logo hydration failed for ${entry.id}", it) }
        }
    }

    companion object {
        private const val LOCAL_LIST_KEY = "local"
    }
}