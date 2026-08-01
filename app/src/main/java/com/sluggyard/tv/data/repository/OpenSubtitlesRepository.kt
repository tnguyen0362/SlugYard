package com.sluggyard.tv.data.repository

import android.content.Context
import android.util.Log
import com.sluggyard.tv.data.local.PlayerSettingsDataStore
import com.sluggyard.tv.data.remote.api.OpenSubtitlesApi
import com.sluggyard.tv.data.remote.dto.opensubtitles.OpenSubtitlesDownloadRequest
import com.sluggyard.tv.data.remote.dto.opensubtitles.OpenSubtitlesSubtitleData
import com.sluggyard.tv.domain.model.Subtitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the OpenSubtitles REST API to search for subtitles and download
 * them into a private cache directory. A user-configured API key is required
 * for both operations.
 */
@Singleton
class OpenSubtitlesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OpenSubtitlesApi,
    private val httpClient: OkHttpClient,
    private val settings: PlayerSettingsDataStore
) {
    private val cacheDir = File(context.cacheDir, "opensubtitles").apply { mkdirs() }
    private var userToken: String? = null

    suspend fun searchSubtitles(
        movieHash: String?,
        imdbId: String?,
        query: String?,
        languages: List<String>,
        type: String?,
        season: Int?,
        episode: Int?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val apiKey = settings.openSubtitlesApiKey.first()
        if (apiKey.isBlank()) return@withContext emptyList()

        try {
            val response = api.searchSubtitles(
                movieHash = movieHash,
                imdbId = imdbId,
                query = query,
                languages = languages.joinToString(","),
                type = type,
                season = season,
                episode = episode,
                apiKey = apiKey
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "OpenSubtitles search failed: ${response.code()}")
                return@withContext emptyList()
            }
            response.body()?.data?.mapNotNull { it.toSubtitle() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "OpenSubtitles search error", e)
            emptyList()
        }
    }

    suspend fun downloadSubtitle(subtitle: Subtitle): File? = withContext(Dispatchers.IO) {
        val apiKey = settings.openSubtitlesApiKey.first()
        if (apiKey.isBlank()) return@withContext null

        try {
            val fileId = subtitle.id.substringAfter("file:").toIntOrNull()
                ?: return@withContext null

            val response = api.downloadSubtitle(
                request = OpenSubtitlesDownloadRequest(fileId = fileId),
                apiKey = apiKey,
                authorization = userToken?.let { "Bearer $it" } ?: ""
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "OpenSubtitles download failed: ${response.code()}")
                return@withContext null
            }

            val downloadResponse = response.body() ?: return@withContext null
            val downloadUrl = downloadResponse.link
            if (downloadUrl.isBlank()) return@withContext null

            val cachedFile = cachedFileFor(subtitle)
            if (cachedFile.exists() && cachedFile.length() > 0) return@withContext cachedFile

            val request = Request.Builder()
                .url(downloadUrl)
                .header("Api-Key", apiKey)
                .apply { userToken?.let { header("Authorization", "Bearer $it") } }
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                cachedFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            }

            if (cachedFile.exists() && cachedFile.length() > 0) cachedFile else null
        } catch (e: Exception) {
            Log.e(TAG, "OpenSubtitles download error", e)
            null
        }
    }

    private fun OpenSubtitlesSubtitleData.toSubtitle(): Subtitle? {
        val file = attributes.files.firstOrNull() ?: return null
        return Subtitle(
            id = "file:${file.fileId}",
            url = "",
            lang = attributes.language,
            addonName = "OpenSubtitles",
            addonLogo = null,
            format = file.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() },
        )
    }

    private fun cachedFileFor(subtitle: Subtitle): File {
        val hash = MessageDigest.getInstance("MD5").digest(subtitle.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val normalizedFormat = subtitle.format?.trim()?.lowercase()?.removePrefix(".")
        val ext = when {
            normalizedFormat in
                setOf("srt", "vtt", "webvtt", "ass", "ssa", "ttml", "dfxp", "sup", "pgs", "idx", "sub") ->
                normalizedFormat.let { if (it == "webvtt") "vtt" else it }
            subtitle.url.endsWith(".vtt", true) -> "vtt"
            subtitle.url.endsWith(".ass", true) -> "ass"
            subtitle.url.endsWith(".ssa", true) -> "ssa"
            else -> "srt"
        }
        return File(cacheDir, "os_${hash}.$ext")
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "OpenSubtitlesRepo"
    }
}
