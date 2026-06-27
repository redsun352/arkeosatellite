package com.arkeosar.satellite.network

import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.model.SatelliteSource

/**
 * Tek bir bandın (örn. NIR, RED, TIR10) bölge için indirilmiş raster verisi.
 * Basitleştirilmiş model: eşit aralıklı bir grid üzerinde değer dizisi.
 * Gerçek GeoTIFF/COG ayrıştırma [GeoTiffDecoder] içinde yapılır.
 */
data class BandRaster(
    val bbox: BoundingBox,
    val widthPx: Int,
    val heightPx: Int,
    val values: FloatArray, // satır-major, boyut = widthPx * heightPx
    val bandName: String
) {
    fun valueAt(row: Int, col: Int): Float = values[row * widthPx + col]
}

/** Bir kaynaktan istenen tüm bantların indirilmiş hâli. */
data class SourceScene(
    val source: SatelliteSource,
    val bands: Map<String, BandRaster>,
    val acquiredEpochMs: Long
)

/**
 * Her uydu kaynağı (GEE/Sentinel-2, Copernicus, USGS/Landsat, ASTER) bu arayüzü
 * implemente eder. GEE için kimlik doğrulama OAuth2/service-account ile,
 * Copernicus/USGS için standart API key/OAuth client-credentials ile yapılır -
 * ama çağıran kod (AnalysisOrchestrator) için fark görünmez.
 */
interface SatelliteDataSource {
    val source: SatelliteSource

    /**
     * Verilen bölge için gerekli bantları indirir.
     * @param bbox sorgu bölgesi (polygon'un bounding box'ı)
     * @param maxAgeDays en güncel kaç gün içindeki sahneyi tercih et (bulutsuz sahne arama toleransı)
     */
    suspend fun fetchScene(bbox: BoundingBox, maxAgeDays: Int = 30): SourceScene
}
