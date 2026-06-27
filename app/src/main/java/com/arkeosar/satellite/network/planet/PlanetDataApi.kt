package com.arkeosar.satellite.network.planet

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

// --- Quick Search ---

data class PlanetSearchRequest(
    val item_types: List<String>,
    val filter: PlanetFilter
)

/** AndFilter ile geometri + tarih + bulut filtrelerini birleştiren yapı. */
data class PlanetFilter(
    val type: String = "AndFilter",
    val config: List<Map<String, Any>>
)

data class PlanetSearchResponse(
    val features: List<PlanetFeature>,
    val _links: Map<String, Any>? = null
)

data class PlanetFeature(
    val id: String,
    val properties: PlanetFeatureProperties,
    val _links: PlanetFeatureLinks
)

data class PlanetFeatureProperties(
    val acquired: String,
    val cloud_cover: Double?
)

data class PlanetFeatureLinks(
    val assets: String,
    val thumbnail: String? = null
)

// --- Asset activation/download ---

data class PlanetAsset(
    val status: String, // "inactive" | "activating" | "active"
    val _links: PlanetAssetLinks
)

data class PlanetAssetLinks(
    val activate: String,
    val `self`: String? = null
)

interface PlanetDataApi {

    @POST("data/v1/quick-search")
    suspend fun quickSearch(
        @Header("Authorization") apiKeyHeader: String,
        @Body request: PlanetSearchRequest
    ): PlanetSearchResponse

    /** Bir item'ın tüm asset'lerini (asset_type -> PlanetAsset map'i) getirir. */
    @GET
    suspend fun getAssets(
        @Url assetsUrl: String,
        @Header("Authorization") apiKeyHeader: String
    ): Map<String, PlanetAsset>

    /** Tek bir asset'in GÜNCEL durumunu sorgular - polling için kullanılır (assets map'i değil, asset'in kendi self-link'i). */
    @GET
    suspend fun getAssetStatus(
        @Url selfUrl: String,
        @Header("Authorization") apiKeyHeader: String
    ): PlanetAsset

    /** Asset'i aktive etmeyi tetikler - 202 (kabul edildi, hazırlanıyor) veya 204 (zaten aktif) döner. */
    @GET
    suspend fun activateAsset(
        @Url activateUrl: String,
        @Header("Authorization") apiKeyHeader: String
    ): Response<Unit>

    /** Aktif bir asset'in gerçek raster verisini indirir. */
    @GET
    suspend fun downloadAsset(
        @Url downloadUrl: String,
        @Header("Authorization") apiKeyHeader: String
    ): Response<okhttp3.ResponseBody>
}
