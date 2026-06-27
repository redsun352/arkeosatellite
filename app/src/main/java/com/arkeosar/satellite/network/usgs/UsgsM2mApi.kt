package com.arkeosar.satellite.network.usgs

import retrofit2.http.Body
import retrofit2.http.POST

// --- Auth ---

/**
 * DOĞRULANDI (28 Haziran 2026): "username" ve "token" alan adları gerçek bir
 * login-token çağrısıyla test edildi ve doğru olduğu kanıtlandı. API, "password"
 * alan adını tanımayıp "token" alanının eksik olduğunu bildirdi - bu da şemanın
 * username+token olduğunu (password değil) kesin olarak doğruluyor.
 */
data class LoginTokenRequest(val username: String, val token: String)

data class M2mEnvelope<T>(
    val requestId: Long?,
    val version: String?,
    val sessionId: String?,
    val data: T?,
    val errorCode: String?,
    val errorMessage: String?
)

// --- Scene search ---

data class SceneSearchRequest(
    val datasetName: String,
    val sceneFilter: SceneFilter,
    val maxResults: Int = 5
)

data class SceneFilter(
    val spatialFilter: SpatialFilter,
    val acquisitionFilter: AcquisitionFilter,
    val cloudCoverFilter: CloudCoverFilter? = null
)

data class SpatialFilter(
    val filterType: String = "mbr", // minimum bounding rectangle
    val lowerLeft: LatLng,
    val upperRight: LatLng
)

data class LatLng(val latitude: Double, val longitude: Double)

data class AcquisitionFilter(val start: String, val end: String) // "YYYY-MM-DD"

data class CloudCoverFilter(val min: Int = 0, val max: Int = 30, val includeUnknown: Boolean = true)

data class SceneSearchResult(
    val results: List<SceneResult>,
    val totalHits: Int?
)

data class SceneResult(
    val entityId: String,
    val displayId: String,
    val cloudCover: String?
)

// --- Download options / request ---

data class DownloadOptionsRequest(val datasetName: String, val entityIds: List<String>)

data class DownloadOption(
    val entityId: String,
    val id: String, // downloadId - download-request'te kullanılır
    val productName: String,
    val available: Boolean
)

data class DownloadRequestBody(val downloads: List<DownloadEntry>)

data class DownloadEntry(val entityId: String, val productId: String)

data class DownloadRequestResult(
    val availableDownloads: List<AvailableDownload>?,
    val preparingDownloads: List<Any>?
)

data class AvailableDownload(val downloadId: Long, val url: String, val entityId: String?)

interface UsgsM2mApi {

    @POST("login-token")
    suspend fun loginToken(@Body request: LoginTokenRequest): M2mEnvelope<String> // data = sessionId benzeri X-Auth token

    @POST("scene-search")
    suspend fun sceneSearch(
        @retrofit2.http.Header("X-Auth-Token") authToken: String,
        @Body request: SceneSearchRequest
    ): M2mEnvelope<SceneSearchResult>

    @POST("download-options")
    suspend fun downloadOptions(
        @retrofit2.http.Header("X-Auth-Token") authToken: String,
        @Body request: DownloadOptionsRequest
    ): M2mEnvelope<List<DownloadOption>>

    @POST("download-request")
    suspend fun downloadRequest(
        @retrofit2.http.Header("X-Auth-Token") authToken: String,
        @Body request: DownloadRequestBody
    ): M2mEnvelope<DownloadRequestResult>
}
