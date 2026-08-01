package com.sluggyard.tv.domain.repository

import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String? = null
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun clearCache()
}
