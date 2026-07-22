package com.sluggyard.tv.data.remote.api

import com.sluggyard.tv.data.remote.dto.opensubtitles.OpenSubtitlesDownloadRequest
import com.sluggyard.tv.data.remote.dto.opensubtitles.OpenSubtitlesDownloadResponse
import com.sluggyard.tv.data.remote.dto.opensubtitles.OpenSubtitlesSearchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Query("moviehash") movieHash: String? = null,
        @Query("imdb_id") imdbId: String? = null,
        @Query("query") query: String? = null,
        @Query("languages") languages: String? = null,
        @Query("type") type: String? = null,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Header("Api-Key") apiKey: String
    ): Response<OpenSubtitlesSearchResponse>

    @POST("download")
    suspend fun downloadSubtitle(
        @Body request: OpenSubtitlesDownloadRequest,
        @Header("Api-Key") apiKey: String,
        @Header("Authorization") authorization: String
    ): Response<OpenSubtitlesDownloadResponse>
}
