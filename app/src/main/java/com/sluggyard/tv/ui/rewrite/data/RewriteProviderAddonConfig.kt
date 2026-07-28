package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val TorrentioManifestUrl = "https://torrentio.strem.fun/manifest.json"
internal const val MediaFusionManifestHost = "https://mediafusionfortheweebs.midnightignite.me"
/** Keyless catalog bootstrap — never embeds a debrid token. */
internal const val MediaFusionBootstrapManifestUrl = "$MediaFusionManifestHost/manifest.json"

data class RewriteConfiguredAddonUrls(
    val torrentioManifestUrl: String,
    val mediaFusionManifestUrl: String?,
)

internal fun torrentioProviderKey(service: DebridService): String = when (service) {
    DebridService.REAL_DEBRID -> "realdebrid"
    DebridService.TORBOX -> "torbox"
    DebridService.PREMIUMIZE -> "premiumize"
}

internal fun buildTorrentioManifestUrl(service: DebridService, apiKey: String): String {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure Torrentio" }
    val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "${TorrentioManifestUrl.removeSuffix("/manifest.json")}/${torrentioProviderKey(service)}=$encoded/manifest.json"
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
