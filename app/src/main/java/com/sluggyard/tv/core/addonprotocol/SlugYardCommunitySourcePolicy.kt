package com.sluggyard.tv.core.addonprotocol

/**
 * Fixed backend manifest allowlist for the locked-down SlugYard product.
 *
 * Provider-specific manifest URLs are generated only for the two allowlisted content sources.
 * Torrent hashes returned by those sources are still resolved locally through the active
 * rewrite-owned provider transport. Torrentio stays provisioned even during a transient upstream
 * outage; endpoint availability is handled by the bounded transport, not by removing a source.
 */
object SlugYardCommunitySourcePolicy {
    val bootstrapManifestUrls: List<String> = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
        "https://torrentio.strem.fun/manifest.json",
        // Keyless catalog only — never a pre-baked encrypted user path with embedded credentials.
        "https://mediafusionfortheweebs.midnightignite.me/manifest.json",
    )
}
