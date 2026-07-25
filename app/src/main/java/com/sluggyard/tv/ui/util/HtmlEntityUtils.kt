package com.sluggyard.tv.ui.util

private val NAMED_HTML_ENTITIES = mapOf(
    "&apos;" to "'",
    "&#39;" to "'",
    "&#039;" to "'",
    "&quot;" to "\"",
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&nbsp;" to " ",
)

/** Decodes the small set of HTML entities addon metadata sometimes leaks into plain-text fields. */
fun String.unescapeHtmlEntities(): String {
    if ('&' !in this) return this
    var result = this
    for ((entity, replacement) in NAMED_HTML_ENTITIES) {
        result = result.replace(entity, replacement)
    }
    return result
}
