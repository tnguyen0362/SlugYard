package com.sluggyard.tv.core.sync.model

object SupabaseMergePolicy {
    fun mergeProfiles(local: List<CloudProfile>, remote: List<CloudProfile>): List<CloudProfile> =
        mergeBy(local, remote, CloudProfile::profileId, CloudProfile::changedAt, ::profileTieBreak)

    fun mergeLibrary(local: List<CloudLibraryItem>, remote: List<CloudLibraryItem>): List<CloudLibraryItem> =
        mergeBy(local, remote, { it.profileId to it.contentId }, CloudLibraryItem::changedAt, ::libraryTieBreak)

    fun mergeAddons(local: List<CloudAddon>, remote: List<CloudAddon>): List<CloudAddon> =
        mergeBy(local, remote, { it.profileId to it.url }, CloudAddon::changedAt, ::addonTieBreak)

    fun mergePlugins(local: List<CloudPlugin>, remote: List<CloudPlugin>): List<CloudPlugin> =
        mergeBy(local, remote, { it.profileId to it.url }, CloudPlugin::changedAt, ::pluginTieBreak)

    fun mergeCollections(local: List<CloudCollection>, remote: List<CloudCollection>): List<CloudCollection> =
        mergeBy(local, remote, CloudCollection::profileId, CloudCollection::changedAt, CloudCollection::collectionsJson)

    fun mergeHomeCatalogSettings(
        local: List<CloudHomeCatalogSettings>,
        remote: List<CloudHomeCatalogSettings>,
    ): List<CloudHomeCatalogSettings> = mergeBy(
        local,
        remote,
        CloudHomeCatalogSettings::profileId,
        CloudHomeCatalogSettings::changedAt,
        CloudHomeCatalogSettings::settingsJson,
    )

    fun mergeProviderCredentials(
        local: List<ProviderCredentialRecord>,
        remote: List<ProviderCredentialRecord>,
    ): List<ProviderCredentialRecord> = mergeBy(
        local,
        remote,
        { it.profileId to it.providerId },
        ProviderCredentialRecord::changedAtEpochMs,
        { "${it.schemaVersion}|${it.ciphertext}" },
    )

    fun mergeProgress(local: List<CloudWatchProgress>, remote: List<CloudWatchProgress>): List<CloudWatchProgress> =
        mergeBy(local, remote, { it.profileId to it.progressKey }, CloudWatchProgress::changedAt, ::progressTieBreak)

    fun mergeWatchedItems(local: List<CloudWatchedItem>, remote: List<CloudWatchedItem>): List<CloudWatchedItem> =
        mergeBy(local, remote, { it.profileId to it.contentId }, CloudWatchedItem::watchedAt, ::watchedTieBreak)

    fun mergeProfileSettings(
        local: List<CloudProfileSettings>,
        remote: List<CloudProfileSettings>,
    ): List<CloudProfileSettings> = mergeBy(
        local,
        remote,
        CloudProfileSettings::profileId,
        CloudProfileSettings::changedAt,
        ::settingsTieBreak,
    )

    fun mergeProgress(local: CloudWatchProgress, remote: CloudWatchProgress): CloudWatchProgress =
        select(local, remote, CloudWatchProgress::changedAt, ::progressTieBreak)

    fun mergeMutations(
        local: List<SyncMutationEnvelope>,
        remote: List<SyncMutationEnvelope>,
    ): List<SyncMutationEnvelope> = mergeBy(
        local,
        remote,
        { listOf(it.ownerUserId, it.domain.name, it.profileId?.toString().orEmpty(), it.recordKey) },
        SyncMutationEnvelope::clientChangedAtEpochMs,
        SyncMutationEnvelope::mutationId,
    )

    private fun <T, K> mergeBy(
        local: List<T>,
        remote: List<T>,
        key: (T) -> K,
        timestamp: (T) -> Long,
        tieBreak: (T) -> String,
    ): List<T> = (local + remote)
        .groupBy(key)
        .values
        .map { candidates -> candidates.reduce { first, second -> select(first, second, timestamp, tieBreak) } }
        .sortedBy { key(it).toString() }

    private fun <T> select(
        first: T,
        second: T,
        timestamp: (T) -> Long,
        tieBreak: (T) -> String,
    ): T = when {
        timestamp(first) > timestamp(second) -> first
        timestamp(second) > timestamp(first) -> second
        tieBreak(first) >= tieBreak(second) -> first
        else -> second
    }

    private fun profileTieBreak(value: CloudProfile) =
        "${value.name}|${value.avatarColorHex}|${value.avatarId.orEmpty()}"

    private fun libraryTieBreak(value: CloudLibraryItem) =
        "${value.name}|${value.poster.orEmpty()}|${value.description.orEmpty()}|${value.genres.sorted()}"

    private fun progressTieBreak(value: CloudWatchProgress) =
        "${value.position}|${value.duration}|${value.videoId}|${value.season}|${value.episode}"

    private fun watchedTieBreak(value: CloudWatchedItem) =
        "${value.title}|${value.contentType}|${value.season}|${value.episode}"

    private fun addonTieBreak(value: CloudAddon) =
        "${value.name.orEmpty()}|${value.enabled}|${value.sortOrder}"

    private fun pluginTieBreak(value: CloudPlugin) =
        "${value.name.orEmpty()}|${value.enabled}|${value.sortOrder}|${value.repoType.orEmpty()}"

    private fun settingsTieBreak(value: CloudProfileSettings) =
        "${value.languageTag}|${value.themeId}|${value.autoPlay}|${value.subtitlesEnabled}|${value.preferredAudioLanguage}"
}
