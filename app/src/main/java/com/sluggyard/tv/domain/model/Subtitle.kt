package com.sluggyard.tv.domain.model

import androidx.compose.runtime.Immutable
import com.sluggyard.tv.ui.util.languageCodeToName

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?,
    val format: String? = null,
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.sluggyard.tv.ui.util.languageCodeToName(code)
    }
}
