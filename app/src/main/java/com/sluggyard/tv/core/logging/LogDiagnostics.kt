package com.sluggyard.tv.core.logging

import java.net.URI

fun String?.rawForLog(): String = this ?: "(null)"

fun String?.urlForLog(): String {
    val raw = this ?: return "(null)"
    if (raw.isBlank()) return raw
    return runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme ?: return@runCatching redactInline(raw)
        val authority = uri.rawAuthority ?: return@runCatching redactInline(raw)
        val origin = "$scheme://$authority"
        val cleanedPath = buildPath(uri)
        val cleanedQuery = buildQuery(uri)
        origin + cleanedPath + cleanedQuery
    }.getOrElse { redactInline(raw) }
}

private fun buildPath(uri: URI): String {
    val rawPath = uri.rawPath.orEmpty()
    if (rawPath.isBlank()) return ""
    val prefix = if (rawPath.startsWith("/")) "/" else ""
    val segments = rawPath.split('/').filter(String::isNotEmpty)
    val cleaned = segments.joinToString("/", prefix) { seg ->
        when {
            seg.length > 80 -> "<redacted>"
            SENSITIVE_PATH.containsMatchIn(seg) -> "<redacted>"
            else -> seg
        }
    }
    return cleaned
}

private fun buildQuery(uri: URI): String {
    val q = uri.rawQuery ?: return ""
    if (q.isBlank()) return ""
    val pairs = q.split('&').map { entry ->
        val k = entry.substringBefore('=', entry)
        val v = entry.substringAfter('=', "")
        if (SENSITIVE_QUERY.matches(k) || v.length > 80) "$k=<redacted>" else entry
    }
    return "?" + pairs.joinToString("&")
}

private fun redactInline(raw: String): String =
    raw.replace(SENSITIVE_INLINE, "$1=<redacted>")

private val SENSITIVE_QUERY = Regex("(?i)(api_?key|token|auth(orization)?|password|secret|signature|nonce|code)")
private val SENSITIVE_PATH = Regex("(?i)(api_?key|token|auth(orization)?|password|secret|signature)=")
private val SENSITIVE_INLINE = Regex("(?i)\\b(api_?key|token|auth(?:orization)?|password|secret|signature|nonce|code)\\s*[:=]\\s*[^&/\\s]+")

fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String {
    val v = this ?: return "(null)"
    if (v.isBlank()) return v
    return if (v.length <= maxLength) v else v.take(maxLength) + "..."
}

fun Throwable.diagnosticSummary(): String {
    val chain = mutableListOf<String>()
    var cur: Throwable? = this
    while (cur != null && chain.size < 6) {
        val cls = cur.javaClass.simpleName.ifBlank { cur.javaClass.name }
        chain.add("$cls: ${cur.message.bodySnippetForLog()}")
        cur = cur.cause
    }
    return chain.joinToString(" <- ")
}