package com.sluggyard.tv.ui.screens.player

/**
 * Resolves the catalog meta id/type used for episode lists and series enrichment.
 *
 * Rewrite play uses the episode id as [contentId] (`tt…:S:E`). Addon meta for that
 * id often returns a single video (or none), so next-episode and pause-overlay
 * description need the series/parent id when available.
 */
object PlayerMetaLookup {
    data class Lookup(val id: String, val type: String)

    fun resolve(
        contentId: String?,
        contentType: String?,
        parentId: String?,
        parentType: String?,
    ): Lookup? {
        val type = parentType?.takeIf { it.isNotBlank() }
            ?: contentType?.takeIf { it.isNotBlank() }
            ?: return null
        val id = parentId?.takeIf { it.isNotBlank() }
            ?: contentId?.takeIf { it.isNotBlank() }?.let(::normalizeSeriesMetaId)
            ?: return null
        return Lookup(id = id, type = type)
    }

    /**
     * Episode-shaped IMDb ids (`tt1234567:1:2`) collapse to the series root so
     * catalog meta returns the full video list.
     */
    fun normalizeSeriesMetaId(id: String): String {
        if (id.startsWith("tt", ignoreCase = true) && id.contains(':')) {
            return id.substringBefore(':')
        }
        return id
    }
}
