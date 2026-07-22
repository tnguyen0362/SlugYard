package com.sluggyard.tv.data.repository

import java.net.URLEncoder

/**
 * Pure Stremio catalog-URL construction. Isolated from [CatalogRepositoryImpl]
 * so the addon-protocol path/query encoding rules can be exercised without a
 * network stack or Android Context.
 */
object CatalogUrlBuilder {

    fun build(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val (basePath, baseQuery) = splitBaseUrl(baseUrl)

        val catalogPath = when {
            extraArgs.isEmpty() -> {
                if (skip > 0) {
                    "$basePath/catalog/$type/$catalogId/skip=$skip.json"
                } else {
                    "$basePath/catalog/$type/$catalogId.json"
                }
            }
            else -> {
                val merged = LinkedHashMap(extraArgs)
                if (!merged.containsKey("skip") && skip > 0) {
                    merged["skip"] = skip.toString()
                }
                val encoded = merged.entries.joinToString("&") { (k, v) ->
                    "${encode(k)}=${encode(v)}"
                }
                "$basePath/catalog/$type/$catalogId/$encoded.json"
            }
        }

        return catalogPath + baseQuery
    }

    private fun splitBaseUrl(baseUrl: String): Pair<String, String> {
        val trimmed = baseUrl.trimEnd('/')
        val queryIndex = trimmed.indexOf('?')
        return if (queryIndex >= 0) {
            trimmed.substring(0, queryIndex).trimEnd('/') to trimmed.substring(queryIndex)
        } else {
            trimmed to ""
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}