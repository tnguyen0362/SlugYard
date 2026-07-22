package com.sluggyard.tv.core.sync.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SupabaseSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs <= nowEpochMs

    fun isUsable(): Boolean =
        userId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresAtEpochMs > 0L
}

internal object SupabaseSessionExpiry {
    private const val EPOCH_SECONDS_CUTOFF = 100_000_000_000L

    // Supabase epoch values below the cutoff are seconds; larger values are already milliseconds.
    fun epochMillis(value: Long): Long = if (value in 0 until EPOCH_SECONDS_CUTOFF) {
        value.saturatingMultiply(1_000L)
    } else {
        value
    }

    fun fromResponse(expiresAt: Long?, expiresIn: Long?, nowEpochMs: Long): Long = when {
        expiresAt != null && expiresAt > 0L -> epochMillis(expiresAt)
        expiresIn != null && expiresIn > 0L -> nowEpochMs + durationMillis(expiresIn)
        else -> 0L
    }

    private fun durationMillis(value: Long): Long = if (value >= EPOCH_SECONDS_CUTOFF) {
        value
    } else {
        value.saturatingMultiply(1_000L)
    }

    private fun Long.saturatingMultiply(multiplier: Long): Long =
        if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier
}

sealed interface SupabaseSessionState {
    data object SignedOut : SupabaseSessionState
    data class Active(val session: SupabaseSession) : SupabaseSessionState
    data object Corrupt : SupabaseSessionState
}

internal object SupabaseSessionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(session: SupabaseSession): String = buildJsonObject {
        put("user_id", session.userId)
        put("access_token", session.accessToken)
        put("refresh_token", session.refreshToken)
        put("expires_at", session.expiresAtEpochMs)
    }.toString()

    fun decode(payload: String): SupabaseSession? = runCatching {
        val values = json.parseToJsonElement(payload).jsonObject
        SupabaseSession(
            userId = values["user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            accessToken = values["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = values["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresAtEpochMs = values["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L,
        ).takeIf(SupabaseSession::isUsable)
    }.getOrNull()
}
