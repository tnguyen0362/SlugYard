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
 *
 * Focused card backdrops ([preferCardBackdropUrl]) retain enough source detail for a 4K TV while
 * Coil decodes to the card's actual on-screen size. Metahub only has soft 480 vs full-HD medium
 * (large is the same full-HD file), so `small` is never used for focused artwork.
 */

/** Landscape art for focused catalog cards — source detail must match a 4K TV card. */
fun preferCardBackdropUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) {
        return upgradeTraktImageSize(url)
    }
    var next = url
    // A focused landscape card can cover more than 780px on a 4K TV.
    if ("image.tmdb.org" in next) {
        next = next
            .replace(Regex("""/t/p/w\d+/"""), "/t/p/w1280/")
            .replace(Regex("""/t/p/h\d+/"""), "/t/p/w1280/")
            .replace("/t/p/original/", "/t/p/w1280/")
            .replace(Regex("""/t/p/(?!w\d+/|h\d+/)[^/]+/"""), "/t/p/w1280/")
        return next
    }
    // Metahub: Cinemeta ships background/medium. medium/large/original are the same ~1080p file;
    // only `small` is smaller — and too soft for focused landscape on TV. Keep medium; do not
    // crush quality to chase decode. Coil card decode is capped at MaxLandscapeDecodeWidthPx.
    if ("images.metahub.space" in next) {
        return next
            .replace("/background/original/", "/background/medium/")
            .replace("/background/large/", "/background/medium/")
            .replace("/background/small/", "/background/medium/")
            .replace("/fanart/original/", "/fanart/medium/")
            .replace("/fanart/large/", "/fanart/medium/")
            .replace("/fanart/small/", "/fanart/medium/")
    }
    // Unknown add-ons own their URL formats; do not silently replace high-quality artwork with a
    // guessed medium variant. Coil still decodes it to the card's exact display dimensions.
    return next
}

fun preferLargePosterUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) return upgradeTraktImageSize(url)
    return tmdbSize(url, posterWidth = 780, backdropWidth = 1280)
}

fun preferTvBackdropUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (isTraktMediaHost(url)) return upgradeTraktImageSize(url)
    // Full-bleed prepare / hero chrome — prefer the larger TV-safe size even when the
    // source URL was a small catalog poster token (w185/w342). Decode is still capped by Coil.
    return tmdbSize(url, posterWidth = 1280, backdropWidth = 1280)
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
    // Hero / poster upgrades may go medium→large; card strip uses preferCardBackdropUrl instead.
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
