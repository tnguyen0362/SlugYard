package com.sluggyard.tv.ui.app.settings

internal fun credentialSubmissionValue(raw: String): String? = raw.trim().takeIf(String::isNotEmpty)
