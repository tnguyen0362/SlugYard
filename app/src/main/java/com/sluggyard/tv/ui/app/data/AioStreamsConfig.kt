package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun aioStreamsServiceId(service: DebridService): String = when (service) {
    DebridService.REAL_DEBRID -> "realdebrid"
    DebridService.TORBOX -> "torbox"
    DebridService.PREMIUMIZE -> "premiumize"
}

/**
 * Extracts the AIOStreams user config from an Exclude-Credentials export
 * (`{ metadata, config }`) or a bare config object.
 */
internal fun aioStreamsConfigFromTemplate(root: JsonObject): JsonObject {
    return root["config"]?.jsonObject ?: root
}

/**
 * Enables the active debrid service with [apiKey] and disables the other SlugYard-supported
 * services so a single Settings key drives the curated template.
 */
internal fun injectAioStreamsCredentials(
    config: JsonObject,
    service: DebridService,
    apiKey: String,
    accessKey: String? = null,
): JsonObject {
    val normalized = apiKey.trim()
    require(normalized.isNotBlank()) { "A Debrid API key is required to configure AIOStreams" }
    val activeId = aioStreamsServiceId(service)
    val managedIds = DebridService.entries.map(::aioStreamsServiceId).toSet()
    val existing = config["services"]?.jsonArray.orEmpty()
    val rewritten = existing.map { element ->
        val obj = element.jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id !in managedIds) return@map element
        buildJsonObject {
            obj.forEach { (key, value) ->
                when (key) {
                    "enabled" -> put("enabled", id == activeId)
                    "credentials" -> if (id == activeId) {
                        putJsonObject("credentials") { put("apiKey", normalized) }
                    } else {
                        putJsonObject("credentials") {}
                    }
                    else -> put(key, value)
                }
            }
            if ("enabled" !in obj.keys) put("enabled", id == activeId)
            if ("credentials" !in obj.keys) {
                if (id == activeId) {
                    putJsonObject("credentials") { put("apiKey", normalized) }
                } else {
                    putJsonObject("credentials") {}
                }
            }
        }
    }
    val hasActive = rewritten.any {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == activeId
    }
    val services = if (hasActive) {
        rewritten
    } else {
        rewritten + buildJsonObject {
            put("id", activeId)
            put("enabled", true)
            putJsonObject("credentials") { put("apiKey", normalized) }
        }
    }
    val normalizedAccessKey = accessKey?.trim().orEmpty()
    return buildJsonObject {
        config.forEach { (key, value) ->
            if (key != "services" && key != "accessKey") put(key, value)
        }
        put("services", JsonArray(services))
        if (normalizedAccessKey.isNotBlank()) {
            put("accessKey", normalizedAccessKey)
        }
    }
}

internal fun decodeAioStreamsCreateResponse(payload: JsonObject): Pair<String, String> {
    val data = payload["data"]?.jsonObject
        ?: throw IllegalArgumentException("AIOStreams returned no user data")
    val uuid = data["uuid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val encryptedPassword = data["encryptedPassword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(uuid.isNotBlank()) { "AIOStreams returned no uuid" }
    require(encryptedPassword.isNotBlank()) { "AIOStreams returned no encryptedPassword" }
    return uuid to encryptedPassword
}

internal fun aioStreamsManifestUrl(baseUrl: String, uuid: String, encryptedPassword: String): String {
    val base = baseUrl.trim().trimEnd('/')
    require(base.isNotBlank()) { "AIOStreams host is required" }
    val normalizedUuid = uuid.trim()
    val normalizedPassword = encryptedPassword.trim()
    require(normalizedUuid.isNotBlank() && '/' !in normalizedUuid) { "AIOStreams returned an invalid uuid" }
    require(normalizedPassword.isNotBlank() && '/' !in normalizedPassword) {
        "AIOStreams returned an invalid encryptedPassword"
    }
    return "$base/stremio/$normalizedUuid/$normalizedPassword/manifest.json"
}

internal fun aioStreamsBootstrapManifestUrl(baseUrl: String): String? {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isBlank()) return null
    return "$base/stremio/manifest.json"
}
