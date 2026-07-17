package com.nuvio.tv.core.logging

import java.net.URI

fun String?.rawForLog(): String =
    this ?: "(null)"

fun String?.urlForLog(): String {
    val raw = this ?: return "(null)"
    if (raw.isBlank()) return raw

    return runCatching {
        val uri = URI(raw)
        val origin = buildString {
            append(uri.scheme ?: return@runCatching sanitizeUrlText(raw))
            append("://")
            append(uri.rawAuthority ?: return@runCatching sanitizeUrlText(raw))
        }
        val path = uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString(separator = "/", prefix = if (uri.rawPath?.startsWith('/') == true) "/" else "") { segment ->
                when {
                    segment.length > 80 -> "<redacted>"
                    SENSITIVE_PATH_CONFIG.containsMatchIn(segment) -> "<redacted>"
                    else -> segment
                }
            }
        val query = uri.rawQuery
            ?.split('&')
            ?.joinToString(separator = "&", prefix = "?") { entry ->
                val key = entry.substringBefore('=', missingDelimiterValue = entry)
                val value = entry.substringAfter('=', missingDelimiterValue = "")
                if (SENSITIVE_QUERY_KEY.matches(key) || value.length > 80) "$key=<redacted>" else entry
            }
            .orEmpty()
        "$origin$path$query"
    }.getOrElse { sanitizeUrlText(raw) }
}

private fun sanitizeUrlText(raw: String): String =
    raw.replace(SENSITIVE_INLINE_VALUE, "$1=<redacted>")

private val SENSITIVE_QUERY_KEY = Regex("(?i)(api_?key|token|auth(orization)?|password|secret|signature|nonce|code)")
private val SENSITIVE_PATH_CONFIG = Regex("(?i)(api_?key|token|auth(orization)?|password|secret|signature)=")
private val SENSITIVE_INLINE_VALUE = Regex("(?i)\\b(api_?key|token|auth(?:orization)?|password|secret|signature|nonce|code)\\s*[:=]\\s*[^&/\\s]+")

fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String {
    val value = this ?: return "(null)"
    if (value.isBlank()) return value
    return if (value.length <= maxLength) value else "${value.take(maxLength)}..."
}

fun Throwable.diagnosticSummary(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
        val message = current.message.bodySnippetForLog()
        parts.add("$name: $message")
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
