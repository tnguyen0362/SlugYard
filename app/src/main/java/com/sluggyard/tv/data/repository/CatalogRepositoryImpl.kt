package com.sluggyard.tv.data.repository

import android.content.Context
import android.util.Log
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.core.network.safeApiCall
import com.sluggyard.tv.core.logging.urlForLog
import com.sluggyard.tv.data.mapper.toDomain
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.domain.model.CatalogRow
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = CatalogUrlBuilder.build(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId " +
                "skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=${url.urlForLog()}"
        )

        val result = safeApiCall(context) { api.getCatalog(url) }
        if (result is NetworkResult.Error) {
            Log.w(
                TAG,
                "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId " +
                    "code=${result.code} message=${result.message} url=${url.urlForLog()}"
            )
            emit(result)
            return@flow
        }
        if (result !is NetworkResult.Success) return@flow

        val items = result.data.metas.map { it.toDomain(type, addonBaseUrl) }.distinctBy { it.id }
        Log.d(TAG, "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}")

        emit(NetworkResult.Success(assembleRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            items = items,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs,
            supportsSkip = supportsSkip
        )))
    }

    private fun assembleRow(
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String,
        items: List<com.sluggyard.tv.domain.model.MetaPreview>,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): CatalogRow {
        val hasResults = items.isNotEmpty()
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = items,
            isLoading = false,
            hasMore = supportsSkip && hasResults,
            currentPage = if (skipStep > 0) skip / skipStep else 0,
            supportsSkip = supportsSkip,
            skipStep = skipStep,
            nextSkip = if (supportsSkip && hasResults) skip + items.size else skip,
            extraArgs = extraArgs
        )
    }
}