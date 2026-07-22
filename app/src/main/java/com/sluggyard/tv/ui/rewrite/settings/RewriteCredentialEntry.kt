package com.sluggyard.tv.ui.rewrite.settings

internal fun credentialSubmissionValue(raw: String): String? = raw.trim().takeIf(String::isNotEmpty)
