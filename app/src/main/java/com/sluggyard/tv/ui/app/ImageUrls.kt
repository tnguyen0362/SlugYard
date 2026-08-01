package com.sluggyard.tv.ui.app

/**
 * TV-safe image URL upgrades.
 *
 * Do **not** force TMDB `/original/` on leanback devices — decoding multi‑megabyte stills on
 * armeabi-v7a (Onn, etc.) OOMs when Play opens the building screen. Prefer a large-but-bounded
 * size that still looks sharp on 1080p/4K TVs.
 *
 * Trakt CDN only serves `thumb` / `medium` / `full` — never rewrite those to `/large/` (404 →
 * blank More Like This / catalog tiles).
 */
fun preferLargePosterUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) return upgradeTraktImageSize(url)
    return tmdbSize(url, posterWidth = 500, backdropWidth = 1280)
}

fun preferTvBackdropUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) return upgradeTraktImageSize(url)
    // Full-bleed prepare / hero chrome — prefer the larger TV-safe size even when the
    // source URL was a small catalog poster token (w185/w342). Decode is still capped by Coil.
    return tmdbSize(url, posterWidth = 1280, backdropWidth = 1280)
}

/** Landscape art for focused catalog cards — w780 matches TMDB catalog storage and
 *  avoids upgrading every focus to a w1280 decode that starved Coil's 3-slot queue. */
fun preferCardBackdropUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) return upgradeTraktImageSize(url)
    return tmdbSize(url, posterWidth = 780, backdropWidth = 780)
}

private fun isTraktMediaHost(url: String): Boolean =
    "media.trakt.tv" in url || "walter.trakt.tv" in url

/** Trakt CDN sizes: thumb < medium < full. `/large/` does not exist. */
private fun upgradeTraktImageSize(url: String): String =
    url
        .replace("/thumb/", "/full/")
        .replace("/medium/", "/full/")

private fun tmdbSize(url: String, posterWidth: Int, backdropWidth: Int): String {
    var next = url
    // Portrait poster tokens → bounded width.
    next = next.replace(Regex("""/t/p/w\d+/"""), "/t/p/w$posterWidth/")
    next = next.replace(Regex("""/t/p/h\d+/"""), "/t/p/w$posterWidth/")
    // Explicit original / oversized tokens → TV-safe sizes.
    if ("image.tmdb.org" in next) {
        next = next
            .replace("/t/p/original/", "/t/p/w$backdropWidth/")
            .replace(Regex("""/t/p/(?!w\d+/|h\d+/)[^/]+/"""), "/t/p/w$backdropWidth/")
    }
    // Generic CDN size folders used by some addons (not Trakt — handled above).
    next = next
        .replace("/medium/", "/large/")
        .replace("/small/", "/large/")
        .replace("/thumb/", "/large/")
        .replace("/original/", "/large/")
    // Non-TMDB absolute paths that still embed w185/w342 style segments.
    next = next.replace(Regex("""/(w\d+|h\d+)/""")) { match ->
        val token = match.groupValues[1]
        if (token.startsWith("h")) "/w$posterWidth/" else "/w$posterWidth/"
    }
    return next
}
