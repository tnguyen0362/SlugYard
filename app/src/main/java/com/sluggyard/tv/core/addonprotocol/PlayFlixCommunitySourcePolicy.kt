package com.sluggyard.tv.core.addonprotocol

/**
 * Fixed backend manifest allowlist for the locked-down PlayFlix product.
 *
 * Provider-specific manifest URLs are generated only for the two allowlisted content sources.
 * Torrent hashes returned by those sources are still resolved locally through the active
 * rewrite-owned provider transport. Torrentio stays provisioned even during a transient upstream
 * outage; endpoint availability is handled by the bounded transport, not by removing a source.
 */
object PlayFlixCommunitySourcePolicy {
    val bootstrapManifestUrls: List<String> = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
        "https://torrentio.strem.fun/manifest.json",
        "https://mediafusionfortheweebs.midnightignite.me/D-JPpZFd2i8K8NFjTEDOFoYYZKfxv0RyIaBXdhVYa9m-ekkXhqkWB4WsahDS15AFA86fjwu7H2tB1wkkRpE-e17SJIhlmj_HX2-sPV0lC_2hg8XDANf_u4AHq1O-VS-C9ViZJWdm8dTGd4nsrFAVm0IjkAC2-M6YGZ2GPbG7nvklg/manifest.json",
    )
}
