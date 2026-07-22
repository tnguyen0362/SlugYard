package com.sluggyard.tv.core.sync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

data class CloudProfile(
    val profileId: Int,
    val name: String,
    val avatarColorHex: String,
    val avatarId: String?,
    val changedAt: Long,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
)

data class CloudLibraryItem(
    val profileId: Int,
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Double? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null,
    val addedAt: Long = 0L,
    val changedAt: Long,
)

data class CloudAddon(
    val profileId: Int,
    val url: String,
    val name: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val changedAt: Long,
)

data class CloudPlugin(
    val profileId: Int,
    val url: String,
    val name: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val repoType: String?,
    val changedAt: Long,
)

data class CloudCollection(
    val profileId: Int,
    val collectionsJson: String,
    val changedAt: Long,
)

data class CloudHomeCatalogSettings(
    val profileId: Int,
    val settingsJson: String,
    val changedAt: Long,
)

data class CloudWatchProgress(
    val profileId: Int,
    val progressKey: String,
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val changedAt: Long = lastWatched,
)

data class CloudWatchedItem(
    val profileId: Int,
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAt: Long,
    val changedAt: Long = watchedAt,
)

data class CloudProfileSettings(
    val profileId: Int,
    val languageTag: String?,
    val themeId: String?,
    val autoPlay: Boolean,
    val subtitlesEnabled: Boolean,
    val preferredAudioLanguage: String?,
    val changedAt: Long,
)

@Serializable
private data class ProfileWire(
    @SerialName("profile_index") val profileId: Int,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("updated_at_epoch_ms") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class LibraryWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String = "",
    val poster: String? = null,
    @SerialName("poster_shape") val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0L,
    @SerialName("updated_at_epoch_ms") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class ProgressWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("progress_key") val progressKey: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    @SerialName("last_watched") val lastWatched: Long,
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
)

@Serializable
private data class WatchedWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long,
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
)

@Serializable
private data class SettingsPayloadWire(
    @SerialName("language_tag") val languageTag: String? = null,
    @SerialName("theme_id") val themeId: String? = null,
    @SerialName("auto_play") val autoPlay: Boolean = true,
    @SerialName("subtitles_enabled") val subtitlesEnabled: Boolean = true,
    @SerialName("preferred_audio_language") val preferredAudioLanguage: String? = null,
)

@Serializable
private data class SettingsWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("settings_json") val settingsJson: SettingsPayloadWire = SettingsPayloadWire(),
    @SerialName("updated_at_epoch_ms") val changedAtEpochMs: Long? = null,
    @SerialName("client_changed_at") val clientChangedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class AddonWire(
    @SerialName("profile_id") val profileId: Int,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class PluginWire(
    @SerialName("profile_id") val profileId: Int,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("repo_type") val repoType: String? = null,
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class CollectionWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("collections_json") val collectionsJson: String = "[]",
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class HomeCatalogSettingsWire(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("settings_json") val settingsJson: String = "{}",
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

@Serializable
private data class ProviderCredentialWire(
    @SerialName("profile_id") val profileId: Int? = null,
    val provider: String,
    @SerialName("credential_ciphertext") val ciphertext: String,
    @SerialName("ciphertext_version") val schemaVersion: Int = 1,
    @SerialName("client_changed_at") val changedAtEpochMs: Long? = null,
    @SerialName("updated_at") val changedAtIso: String? = null,
)

object SupabaseSyncJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decodeProfile(payload: String): CloudProfile? = decode<ProfileWire>(payload)?.let {
        CloudProfile(
            it.profileId,
            it.name,
            it.avatarColorHex,
            it.avatarId,
            it.changedAtEpochMs ?: it.changedAtIso.toEpochMillis(),
            it.usesPrimaryAddons,
            it.usesPrimaryPlugins,
        )
    }

    fun decodeLibraryItem(payload: String): CloudLibraryItem? = decode<LibraryWire>(payload)?.toDomain()

    fun decodeAddon(payload: String): CloudAddon? = decode<AddonWire>(payload)?.toDomain()

    fun decodePlugin(payload: String): CloudPlugin? = decode<PluginWire>(payload)?.toDomain()

    fun decodeCollection(payload: String): CloudCollection? = decode<CollectionWire>(payload)?.toDomain()

    fun decodeHomeCatalogSettings(payload: String): CloudHomeCatalogSettings? =
        decode<HomeCatalogSettingsWire>(payload)?.toDomain()

    fun decodeProviderCredential(payload: String): ProviderCredentialRecord? =
        decode<ProviderCredentialWire>(payload)?.toDomain()

    fun decodeProgress(payload: String): CloudWatchProgress? = decode<ProgressWire>(payload)?.toDomain()

    fun decodeWatchedItem(payload: String): CloudWatchedItem? = decode<WatchedWire>(payload)?.toDomain()

    fun decodeProfileSettings(payload: String): CloudProfileSettings? = decode<SettingsWire>(payload)?.toDomain()

    fun decodeProfiles(payload: String): List<CloudProfile>? = decodeList(payload, ::decodeProfile)

    fun decodeLibraryItems(payload: String): List<CloudLibraryItem>? = decodeList(payload, ::decodeLibraryItem)

    fun decodeAddons(payload: String): List<CloudAddon>? = decodeList(payload, ::decodeAddon)

    fun decodePlugins(payload: String): List<CloudPlugin>? = decodeList(payload, ::decodePlugin)

    fun decodeCollections(payload: String): List<CloudCollection>? = decodeList(payload, ::decodeCollection)

    fun decodeHomeCatalogSettingsItems(payload: String): List<CloudHomeCatalogSettings>? =
        decodeList(payload, ::decodeHomeCatalogSettings)

    fun decodeProviderCredentials(payload: String): List<ProviderCredentialRecord>? =
        decodeList(payload, ::decodeProviderCredential)

    fun decodeProgressItems(payload: String): List<CloudWatchProgress>? = decodeList(payload, ::decodeProgress)

    fun decodeWatchedItems(payload: String): List<CloudWatchedItem>? = decodeList(payload, ::decodeWatchedItem)

    fun decodeProfileSettingsItems(payload: String): List<CloudProfileSettings>? =
        decodeList(payload, ::decodeProfileSettings)

    fun encodeProfile(value: CloudProfile, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_index", value.profileId)
            put("name", value.name)
            put("avatar_color_hex", value.avatarColorHex)
            put("uses_primary_addons", value.usesPrimaryAddons)
            put("uses_primary_plugins", value.usesPrimaryPlugins)
            value.avatarId?.let { put("avatar_id", it) }
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeLibraryItem(value: CloudLibraryItem, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("content_id", value.contentId)
            put("content_type", value.contentType)
            put("name", value.name)
            value.poster?.let { put("poster", it) }
            put("poster_shape", value.posterShape)
            value.background?.let { put("background", it) }
            value.description?.let { put("description", it) }
            value.releaseInfo?.let { put("release_info", it) }
            value.imdbRating?.let { put("imdb_rating", it) }
            put("genres", JsonArray(value.genres.map(::JsonPrimitive)))
            value.addonBaseUrl?.let { put("addon_base_url", it) }
            put("added_at", value.addedAt)
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeProgress(value: CloudWatchProgress, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("progress_key", value.progressKey)
            put("content_id", value.contentId)
            put("content_type", value.contentType)
            put("video_id", value.videoId)
            value.season?.let { put("season", it) }
            value.episode?.let { put("episode", it) }
            put("position", value.position)
            put("duration", value.duration)
            put("last_watched", value.lastWatched)
            put("client_changed_at", value.changedAt)
        },
    )

    fun encodeWatchedItem(value: CloudWatchedItem, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("content_id", value.contentId)
            put("content_type", value.contentType)
            put("title", value.title)
            value.season?.let { put("season", it) }
            value.episode?.let { put("episode", it) }
            put("watched_at", value.watchedAt)
            put("client_changed_at", value.changedAt)
        },
    )

    fun encodeProfileSettings(value: CloudProfileSettings, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("settings_json", buildJsonObject {
                value.languageTag?.let { put("language_tag", it) }
                value.themeId?.let { put("theme_id", it) }
                put("auto_play", value.autoPlay)
            put("subtitles_enabled", value.subtitlesEnabled)
                value.preferredAudioLanguage?.let { put("preferred_audio_language", it) }
            })
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeAddon(value: CloudAddon, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("url", value.url)
            value.name?.let { put("name", it) }
            put("enabled", value.enabled)
            put("sort_order", value.sortOrder)
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodePlugin(value: CloudPlugin, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("url", value.url)
            value.name?.let { put("name", it) }
            put("enabled", value.enabled)
            put("sort_order", value.sortOrder)
            value.repoType?.let { put("repo_type", it) }
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeCollection(value: CloudCollection, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("collections_json", value.collectionsJson)
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeHomeCatalogSettings(value: CloudHomeCatalogSettings, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            put("profile_id", value.profileId)
            put("settings_json", value.settingsJson)
            put("client_changed_at", value.changedAt)
            put("updated_at", value.changedAt.toIsoTimestamp())
        },
    )

    fun encodeProviderCredential(value: ProviderCredentialRecord, userId: String): String = json.encodeToString(
        buildJsonObject {
            put("user_id", userId)
            if (value.profileId != null) put("profile_id", value.profileId) else put("profile_id", kotlinx.serialization.json.JsonNull)
            put("provider", value.providerId)
            put("credential_ciphertext", value.ciphertext)
            put("ciphertext_version", value.schemaVersion)
            put("client_changed_at", value.changedAtEpochMs)
            put("updated_at", value.changedAtEpochMs.toIsoTimestamp())
        },
    )

    private inline fun <reified T> decode(payload: String): T? = runCatching {
        json.decodeFromString<T>(payload)
    }.getOrNull()

    private fun <T> decodeList(payload: String, decoder: (String) -> T?): List<T>? = runCatching {
        json.parseToJsonElement(payload).jsonArray.map { element ->
            decoder(element.toString()) ?: error("Invalid sync record")
        }
    }.getOrNull()

    private fun LibraryWire.toDomain() = CloudLibraryItem(
        profileId,
        contentId,
        contentType,
        name,
        poster,
        posterShape,
        background,
        description,
        releaseInfo,
        imdbRating,
        genres.filter(String::isNotBlank),
        addonBaseUrl,
        addedAt,
        changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun AddonWire.toDomain() = CloudAddon(
        profileId = profileId,
        url = url,
        name = name,
        enabled = enabled,
        sortOrder = sortOrder,
        changedAt = changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun PluginWire.toDomain() = CloudPlugin(
        profileId = profileId,
        url = url,
        name = name,
        enabled = enabled,
        sortOrder = sortOrder,
        repoType = repoType,
        changedAt = changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun CollectionWire.toDomain() = CloudCollection(
        profileId = profileId,
        collectionsJson = collectionsJson,
        changedAt = changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun HomeCatalogSettingsWire.toDomain() = CloudHomeCatalogSettings(
        profileId = profileId,
        settingsJson = settingsJson,
        changedAt = changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun ProviderCredentialWire.toDomain() = ProviderCredentialRecord(
        profileId = profileId,
        providerId = provider,
        ciphertext = ciphertext,
        schemaVersion = schemaVersion,
        changedAtEpochMs = changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun ProgressWire.toDomain() = CloudWatchProgress(
        profileId,
        progressKey,
        contentId,
        contentType,
        videoId,
        season,
        episode,
        position.coerceAtLeast(0L),
        duration.coerceAtLeast(0L),
        lastWatched,
        changedAtEpochMs ?: lastWatched,
    )

    private fun WatchedWire.toDomain() = CloudWatchedItem(
        profileId,
        contentId,
        contentType,
        title,
        season,
        episode,
        watchedAt,
        changedAtEpochMs ?: watchedAt,
    )

    private fun SettingsWire.toDomain() = CloudProfileSettings(
        profileId,
        settingsJson.languageTag,
        settingsJson.themeId,
        settingsJson.autoPlay,
        settingsJson.subtitlesEnabled,
        settingsJson.preferredAudioLanguage,
        clientChangedAtEpochMs ?: changedAtEpochMs ?: changedAtIso.toEpochMillis(),
    )

    private fun Long.toIsoTimestamp() = java.time.Instant.ofEpochMilli(this).toString()

    private fun String?.toEpochMillis(): Long = runCatching {
        this?.let(java.time.Instant::parse)?.toEpochMilli() ?: 0L
    }.getOrDefault(0L)
}
