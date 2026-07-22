package com.sluggyard.tv.core.sync.model

data class ProviderCredentialRecord(
    val profileId: Int?,
    val providerId: String,
    val ciphertext: String,
    val schemaVersion: Int,
    val changedAtEpochMs: Long,
)
