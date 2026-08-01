package com.sluggyard.tv.core.aggregation

/** A stable identity for a catalog declared by a particular addon. */
data class HomeCatalogKey(
    val addonId: String,
    val catalogId: String,
)

/**
 * Applies the persisted Home order without allowing stale preferences to hide new catalogs.
 *
 * [availableInDefaultOrder] is normally manifest declaration order with addons ordered by the
 * user's addon order. Saved keys that no longer exist are ignored; every newly discovered key is
 * appended in its default position. Duplicate keys are collapsed so the UI cannot render a row
 * twice after a malformed preference migration.
 */
fun orderedHomeCatalogs(
    availableInDefaultOrder: List<HomeCatalogKey>,
    savedOrder: List<HomeCatalogKey>,
): List<HomeCatalogKey> {
    val available = availableInDefaultOrder.toLinkedSet()
    val ordered = LinkedHashSet<HomeCatalogKey>(available.size)

    savedOrder.forEach { key ->
        if (key in available) ordered += key
    }
    available.forEach(ordered::add)

    return ordered.toList()
}

/**
 * Produces a deterministic hero sequence for the current candidate data set.
 *
 * Existing eligible hero identities keep their previous order. Only candidates that genuinely
 * changed or newly appeared are appended in the supplied source order, preventing unrelated UI
 * state changes from visibly shuffling the hero.
 */
fun <T, Key> stableHeroOrder(
    candidates: List<T>,
    previousKeys: List<Key>,
    keyOf: (T) -> Key,
    maxItems: Int,
): List<T> {
    require(maxItems >= 0) { "maxItems cannot be negative" }
    if (maxItems == 0 || candidates.isEmpty()) return emptyList()

    val byKey = LinkedHashMap<Key, T>()
    candidates.forEach { candidate -> byKey.putIfAbsent(keyOf(candidate), candidate) }
    val orderedKeys = LinkedHashSet<Key>(byKey.size)

    previousKeys.forEach { key ->
        if (key in byKey) orderedKeys += key
    }
    byKey.keys.forEach(orderedKeys::add)

    return orderedKeys.asSequence()
        .mapNotNull(byKey::get)
        .take(maxItems)
        .toList()
}

private fun <T> List<T>.toLinkedSet(): LinkedHashSet<T> = LinkedHashSet<T>(size).also {
    forEach(it::add)
}
