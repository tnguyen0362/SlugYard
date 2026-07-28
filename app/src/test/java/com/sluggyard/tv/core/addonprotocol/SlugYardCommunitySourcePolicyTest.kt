package com.sluggyard.tv.core.addonprotocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlugYardCommunitySourcePolicyTest {
    @Test
    fun `keeps both community sources in the fixed backend allowlist`() {
        val urls = SlugYardCommunitySourcePolicy.bootstrapManifestUrls

        assertTrue(urls.any { it.contains("torrentio", ignoreCase = true) })
        assertTrue(urls.any { it.contains("mediafusion", ignoreCase = true) })
    }

    @Test
    fun `never embeds a customer debrid credential in a backend manifest URL`() {
        assertTrue(SlugYardCommunitySourcePolicy.bootstrapManifestUrls.isNotEmpty())
        assertFalse(SlugYardCommunitySourcePolicy.bootstrapManifestUrls.any { it.contains("apikey=", ignoreCase = true) })
        assertFalse(
            SlugYardCommunitySourcePolicy.bootstrapManifestUrls.any {
                it.contains("/D-", ignoreCase = false) || it.contains("encrypted", ignoreCase = true)
            },
        )
    }
}
