package com.arkeosar.satellite.network.usgs

import retrofit2.http.Body
import retrofit2.http.POST

// --- Auth ---

/**
 * NOT: "username" ve "token" alan adları, login-token endpoint'inin login endpoint'iyle
 * aynı şema mantığını izlediği varsayımına dayanıyor (login: username+password,
 * login-token: username+token). Bu, resmi USGS PDF dokümantasyonu (sadece tarayıcıdan
 * indirilebilir, bu ortamda metne çevrilemedi) yerine üçüncü parti kütüphane referanslarından
 * çıkarılmıştır. GERÇEK BİR İSTEKLE DOĞRULANMADAN production'a alınmamalı - ilk test
 * çağrısında errorCode="AUTH_INVALID" gibi bir hata dönerse, alan adı yanlış olabilir;
 * USGS M2M JSON API dokümantasyonunu (m2m.cr.usgs.gov/api/docs/json/) kontrol edin.
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
