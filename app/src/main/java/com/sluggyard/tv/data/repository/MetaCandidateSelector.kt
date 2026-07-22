package com.sluggyard.tv.data.repository

import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.AddonResource

/**
 * Pure addon-selection logic for meta lookups, split out of [MetaRepositoryImpl]
 * so the "which installed addon should serve this id/type" priority rules can
 * be unit tested without a network stack or Android Context.
 */
object MetaCandidateSelector {

    private val KNOWN_TYPES = setOf("movie", "series", "tv", "channel", "anime")

    fun inferCanonicalType(type: String, id: String): String {
        val trimmed = type.trim()
        if (trimmed.lowercase() in KNOWN_TYPES) return trimmed

        val loweredId = id.lowercase()
        return when {
            ":movie:" in loweredId -> "movie"
            ":series:" in loweredId -> "series"
            ":tv:" in loweredId -> "tv"
            ":anime:" in loweredId -> "anime"
            else -> trimmed
        }
    }

    fun supportsMetaType(addon: Addon, type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return addon.resources.any { resource ->
            resource.name == "meta" && resource.matchesType(target)
        }
    }

    /**
     * True when:
     * - the addon declares no idPrefixes (accepts all IDs), or
     * - the resource-level idPrefixes match the ID, or
     * - the addon-level idPrefixes match the ID.
     */
    fun supportsMetaId(addon: Addon, id: String): Boolean {
        val metaResource = addon.resources.firstOrNull { it.name == "meta" }
        if (metaResource?.idPrefixes != null && metaResource.idPrefixes.isNotEmpty()) {
            return metaResource.idPrefixes.any { id.startsWith(it, ignoreCase = true) }
        }
        if (addon.idPrefixes.isNotEmpty()) {
            return addon.idPrefixes.any { id.startsWith(it, ignoreCase = true) }
        }
        return true
    }

    private fun AddonResource.matchesType(type: String): Boolean =
        types.isEmpty() || types.any { it.equals(type, ignoreCase = true) }

    /**
     * Priority order:
     * 1) addons that explicitly support the requested type AND the ID prefix
     * 2) addons that support the inferred canonical type AND the ID prefix
     * 3) the first addon (in installed order) exposing a meta resource that supports the id
     * 4) (fallback only when 1-3 found nothing) addons that support the type but
     *    declare no idPrefixes, plus the first such addon
     */
    fun selectPrioritizedCandidates(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String,
        id: String
    ): List<Pair<Addon, String>> {
        val metaAddons = addons.filter { addon -> addon.resources.any { it.name == "meta" } }
        val candidates = linkedSetOf<Pair<Addon, String>>()

        addons.forEach { addon ->
            if (supportsMetaType(addon, requestedType) && supportsMetaId(addon, id)) {
                candidates.add(addon to requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (supportsMetaType(addon, inferredType) && supportsMetaId(addon, id)) {
                    candidates.add(addon to inferredType)
                }
            }
        }
        metaAddons.firstOrNull { supportsMetaId(it, id) }?.let { topMetaAddon ->
            candidates.add(topMetaAddon to fallbackType(topMetaAddon, requestedType, inferredType))
        }

        if (candidates.isEmpty()) {
            addons.forEach { addon ->
                if (supportsMetaType(addon, requestedType) && addon.idPrefixes.isEmpty()) {
                    candidates.add(addon to requestedType)
                }
            }
            metaAddons.firstOrNull { it.idPrefixes.isEmpty() }?.let { topMetaAddon ->
                candidates.add(topMetaAddon to fallbackType(topMetaAddon, requestedType, inferredType))
            }
        }

        return candidates.toList()
    }

    fun selectPrimaryCandidate(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String
    ): Pair<Addon, String>? {
        addons.forEach { addon ->
            if (supportsMetaType(addon, requestedType)) return addon to requestedType
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (supportsMetaType(addon, inferredType)) return addon to inferredType
            }
        }
        val topMetaAddon = addons.firstOrNull { addon -> addon.resources.any { it.name == "meta" } }
            ?: return null
        return topMetaAddon to fallbackType(topMetaAddon, requestedType, inferredType)
    }

    private fun fallbackType(addon: Addon, requestedType: String, inferredType: String): String = when {
        supportsMetaType(addon, requestedType) -> requestedType
        supportsMetaType(addon, inferredType) -> inferredType
        else -> inferredType.ifBlank { requestedType }
    }
}