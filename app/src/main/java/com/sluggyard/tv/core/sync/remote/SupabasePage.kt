package com.sluggyard.tv.core.sync.remote

import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.model.SyncDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SupabasePage(
    val domain: SyncDomain,
    val records: List<JsonElement>,
    val nextCursor: String?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromResponse(domain: SyncDomain, response: SupabaseHttpResponse): SupabasePage =
            when (val result = tryFromResponse(domain, response)) {
                is PageResult.Success -> result.page
                is PageResult.Failure -> error("Supabase page failed: ${result.kind}")
            }

        fun tryFromResponse(domain: SyncDomain, response: SupabaseHttpResponse): PageResult = runCatching {
            if (!response.isSuccessful) {
                return PageResult.Failure(response.failureKind())
            }
            val body = response.body ?: return PageResult.Failure(SyncFailureKind.Decode)
            val records = json.parseToJsonElement(body).jsonArray
            PageResult.Success(
                SupabasePage(
                    domain = domain,
                    records = records,
                    nextCursor = response.headers["x-next-cursor"]
                        ?: response.headers["content-range"]?.nextOffset(),
                ),
            )
        }.getOrElse { PageResult.Failure(SyncFailureKind.Decode) }

        private fun SupabaseHttpResponse.failureKind(): SyncFailureKind = when (code) {
            401 -> SyncFailureKind.Unauthorized
            403 -> SyncFailureKind.Forbidden
            409 -> SyncFailureKind.Conflict
            429 -> SyncFailureKind.RateLimited
            0 -> SyncFailureKind.Network
            in 500..599 -> SyncFailureKind.Server
            else -> SyncFailureKind.Decode
        }
    }
}

private fun String.nextOffset(): String? {
    val range = substringBefore('/').split('-')
    if (range.size != 2) return null
    val end = range[1].toIntOrNull() ?: return null
    val total = substringAfter('/', "*").toIntOrNull() ?: return null
    return (end + 1).takeIf { it < total }?.toString()
}

sealed interface PageResult {
    data class Success(val page: SupabasePage) : PageResult
    data class Failure(val kind: SyncFailureKind) : PageResult
}

sealed interface MutationDisposition {
    data class Accepted(val duplicate: Boolean) : MutationDisposition
    data object Stale : MutationDisposition
    data object Conflict : MutationDisposition

    companion object {
        fun fromJson(payload: String): MutationDisposition {
            val root = Json.parseToJsonElement(payload).jsonObject
            if (root["accepted"]?.jsonPrimitive?.content == "true") {
                return Accepted(root["duplicate"]?.jsonPrimitive?.content == "true")
            }
            return when (root["reason"]?.jsonPrimitive?.content) {
                "stale_or_conflicting" -> Stale
                else -> Conflict
            }
        }
    }
}

data class EventPage(
    val domain: SyncDomain,
    val records: List<JsonElement>,
    val nextCursor: Long?,
)
