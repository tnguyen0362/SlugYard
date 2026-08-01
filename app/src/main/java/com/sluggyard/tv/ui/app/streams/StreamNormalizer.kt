package com.sluggyard.tv.ui.app.streams

/**
 * Normalizes Stremio addon stream payloads the way the legacy [com.sluggyard.tv.domain.model.Stream]
 * model does: extract infohashes from torrent:// and magnet URLs, and refuse to treat debrid
 * proxy / resolve endpoints as instant playable URLs.
 *
 * Without this, Torrentio often returns URL-only streams that auto-pick treats as "direct" and
 * that trigger "torrent is being downloaded" on TorBox when ExoPlayer hits them.
 */
internal data class NormalizedStreamSource(
    val playableDirectUrl: String?,
    val infoHash: String?,
    val isTorrentOrDebridProxy: Boolean,
)

internal fun normalizeAddonStreamSource(
    directUrl: String?,
    infoHash: String?,
): NormalizedStreamSource {
    val rawUrl = directUrl?.trim()?.takeIf { it.isNotBlank() }
    val declaredHash = infoHash?.trim()?.takeIf { it.isNotBlank() }
    val extractedHash = rawUrl?.let { extractInfoHashFromUrl(it) }
    val hash = declaredHash ?: extractedHash
    val isProxyOrTorrent = rawUrl != null && isNonInstantStreamUrl(rawUrl)
    val playable = when {
        rawUrl == null -> null
        isProxyOrTorrent -> null
        hash != null && isProxyOrTorrent -> null
        else -> rawUrl
    }
    return NormalizedStreamSource(
        playableDirectUrl = playable,
        infoHash = hash,
        isTorrentOrDebridProxy = hash != null || isProxyOrTorrent,
    )
}

internal fun isNonInstantStreamUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    if (lower.startsWith("magnet:")) return true
    if (lower.startsWith("torrent:")) return true
    if ("infohash=" in lower) return true
    if ("/createtorrent" in lower || "/torrents/create" in lower) return true
    if ("/resolve/" in lower) return true
    if ("torrentio.strem.fun" in lower) return true
    if ("api.torbox.app" in lower && ("/torrents/" in lower || "createtorrent" in lower)) return true
    return false
}

private fun extractInfoHashFromUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.startsWith("magnet:", ignoreCase = true)) {
        val btih = trimmed.substringAfter("urn:btih:", "")
            .substringBefore('&')
            .substringBefore('#')
        return normalizeHash(btih)
    }
    if (trimmed.startsWith("torrent:", ignoreCase = true)) {
        val clean = trimmed.substringAfter("torrent://").substringAfter("torrent:")
            .substringBefore('?')
            .trimEnd('/')
        return normalizeHash(clean.substringBefore('/'))
    }
    // Torrentio / MediaFusion style: …?infoHash=HEX or …/infoHash/HEX/…
    Regex(
        """(?:[?&/](?:info[_-]?hash)=?)([a-fA-F0-9]{40}|[a-fA-F0-9]{32})\b""",
        RegexOption.IGNORE_CASE,
    )
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return normalizeHash(it) }
    // …/resolve/<provider>/<40-hex>/… (Torrentio debrid proxy)
    Regex("""/resolve/[^/]+/([a-fA-F0-9]{40}|[a-fA-F0-9]{32})(?:/|$)""")
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return normalizeHash(it) }
    // Last resort: any standalone 40-char hex token in the path (avoid query junk).
    Regex("""/(?:([a-fA-F0-9]{40}))(?:/|$)""")
        .find(trimmed.substringBefore('?'))
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return normalizeHash(it) }
    return null
}

private fun normalizeHash(raw: String?): String? {
    val hash = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return hash.takeIf { it.length == 40 || it.length == 32 }
}
