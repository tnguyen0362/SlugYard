package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val TorrentioManifestUrl = "https://torrentio.strem.fun/manifest.json"
internal const val CometManifestHost = "https://comet.elfhosted.com"
internal const val CometManifestUrl = "$CometManifestHost/manifest.json"
internal const val MeteorManifestHost = "https://meteorfortheweebs.midnightignite.me"
internal const val MeteorManifestUrl = "$MeteorManifestHost/manifest.json"
internal const val MediaFusionManifestHost = "https://mediafusionfortheweebs.midnightignite.me"
/** Keyless MediaFusion catalog bootstrap — never embeds a debrid token. */
internal const val MediaFusionBootstrapManifestUrl =
    com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL

data class ConfiguredAddonUrls(
    val torrentioManifestUrl: String,
    val mediaFusionManifestUrl: String?,
    val cometManifestUrl: String? = null,
    val meteorManifestUrl: String? = null,
    val aioStreamsManifestUrl: String? = null,
)

internal fun torrentioProviderKey(service: DebridService): String = when (service) {
    DebridService.REAL_DEBRID -> "realdebrid"
    DebridService.TORBOX -> "torbox"
    DebridService.PREMIUMIZE -> "premiumize"
}

internal fun cometProviderKey(service: DebridService): String = torrentioProviderKey(service)

internal fun buildTorrentioManifestUrl(service: DebridService, apiKey: String): String {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure Torrentio" }
    val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()).replace("+", "%20")
    // Include Download (uncached) rows: TorBox Instant pools are often empty for niche
    // episodes, and Sources / uncached auto-play now force local debrid resolve.
    return "${TorrentioManifestUrl.removeSuffix("/manifest.json")}/" +
        "${torrentioProviderKey(service)}=$encoded/manifest.json"
}

/**
 * Comet cache-only manifest with the active debrid key injected.
 * Matches the user's preferred ElfHosted Comet settings (cachedOnly, no P2P torrents).
 */
internal fun buildCometManifestUrl(service: DebridService, apiKey: String): String {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure Comet" }
    val config = buildJsonObject {
        put("maxResultsPerResolution", 0)
        put("maxSize", 0)
        put("cachedOnly", true)
        put("sortCachedUncachedTogether", false)
        put("removeTrash", true)
        putJsonArray("resultFormat") { add("all") }
        putJsonArray("debridServices") {
            add(
                buildJsonObject {
                    put("service", cometProviderKey(service))
                    put("apiKey", normalized)
                },
            )
        }
        put("enableTorrent", false)
        put("deduplicateStreams", false)
        put("scrapeDebridAccountTorrents", false)
        put("debridStreamProxyPassword", "")
        putJsonObject("languages") {
            putJsonArray("required") {}
            putJsonArray("allowed") {}
            putJsonArray("exclude") {}
            putJsonArray("preferred") {}
        }
        putJsonObject("resolutions") {}
        putJsonObject("options") {
            put("remove_ranks_under", -10000000000.0)
            put("allow_english_in_languages", false)
            put("remove_unknown_languages", false)
        }
    }
    val encoded = Base64.getEncoder().encodeToString(
        config.toString().toByteArray(StandardCharsets.UTF_8),
    )
    return "$CometManifestHost/$encoded/manifest.json"
}

/**
 * Meteor cache-only manifest with the active debrid key injected.
 * Mirrors the user's MidnightIgnite Meteor configure settings (cachedOnly, no P2P,
 * preferred multi, SeaDex sort slot kept even when enableSeaDex is false).
 */
internal fun buildMeteorManifestUrl(service: DebridService, apiKey: String): String {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure Meteor" }
    val config = buildJsonObject {
        put("cachedOnly", true)
        put("skipReleaseFilter", true)
        put("removeTrash", false)
        put("removeSamples", false)
        put("allowAdult", true)
        put("exclude3D", false)
        put("enableSeaDex", false)
        put("showYourMedia", false)
        put("yourMediaLegacyMode", false)
        put("minSeeders", 0)
        put("maxResults", 0)
        put("maxPerResolution", 0)
        putJsonArray("resolutions") {}
        putJsonArray("preferredLangs") { add("multi") }
        putJsonArray("languages") {}
        putJsonArray("excludedLangs") {}
        putJsonArray("resultFormat") {
            add("title")
            add("quality")
            add("size")
            add("audio")
            add("sublang")
            add("audiolang")
        }
        put("languageFormat", "codes")
        putJsonArray("sortOrder") {
            add("cached")
            add("seadex")
            add("seeders")
            add("-resolution")
            add("quality")
            add("language")
            add("size")
            add("pack")
        }
        put("allowP2P", false)
        putJsonArray("excludedSources") {}
        putJsonArray("debridServices") {
            add(
                buildJsonObject {
                    put("service", cometProviderKey(service))
                    put("apiKey", normalized)
                },
            )
        }
    }
    val encoded = Base64.getEncoder().encodeToString(
        config.toString().toByteArray(StandardCharsets.UTF_8),
    )
    return "$MeteorManifestHost/$encoded/manifest.json"
}

/**
 * Builds a MediaFusion encrypt request body. Callers must only POST this to a
 * first-party / self-hosted MediaFusion encrypt URL — never to a shared third-party host
 * with a live debrid token (see BUG-032).
 */
internal fun mediaFusionProviderPayload(service: DebridService, apiKey: String): JsonObject {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure MediaFusion" }
    return buildJsonObject {
        put("name", service.mediaFusionDisplayName())
        put("service", torrentioProviderKey(service))
        put("token", normalized)
        put("priority", 0)
        put("enabled", true)
    }
}

internal fun decodeMediaFusionEncryptedPath(payload: JsonObject): String =
    payload["encrypted_str"]?.toString()?.trim('"')?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("MediaFusion returned no configured manifest")

internal fun mediaFusionManifestUrl(host: String, encryptedPath: String): String {
    val base = host.trim().trimEnd('/')
    require(base.isNotBlank()) { "MediaFusion host is required" }
    val normalized = encryptedPath.trim()
    require(normalized.isNotBlank() && '/' !in normalized) { "MediaFusion returned an invalid configured manifest" }
    return "$base/$normalized/manifest.json"
}

private fun DebridService.mediaFusionDisplayName(): String = when (this) {
    DebridService.REAL_DEBRID -> "Real-Debrid"
    DebridService.TORBOX -> "TorBox"
    DebridService.PREMIUMIZE -> "Premiumize"
}
