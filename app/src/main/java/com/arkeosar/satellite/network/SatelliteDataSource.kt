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
 * Sorgulanacak tarih aralığını temsil eder - LocalDate tabanlı (saat dilimi/saat
 * bilgisi taşımaz, sadece takvim günü). Her uydu kaynağı, kendi API'sinin beklediği
 * formata (ISO-8601 datetime, "YYYY-MM-DD" vb.) kendi içinde çevirir - bu sayede
 * tek bir DateRange modeli tüm kaynaklarla uyumlu kalır.
 *
 * Crop-mark/bitki örtüsü stresi tespiti mevsime çok bağımlıdır (bkz. AnalysisOrchestrator
 * ve MainActivity'deki mevsim seçici notları) - bu yüzden "son N gün" yerine kullanıcının
 * (ya da önceden tanımlı mevsim presetlerinin) belirlediği mutlak bir aralık kullanılır.
 */
data class DateRange(val from: java.time.LocalDate, val to: java.time.LocalDate)

/**
 * Her uydu kaynağı (GEE/Sentinel-2, Copernicus, USGS/Landsat, ASTER) bu arayüzü
 * implemente eder. GEE için kimlik doğrulama OAuth2/service-account ile,
 * Copernicus/USGS için standart API key/OAuth client-credentials ile yapılır -
 * ama çağıran kod (AnalysisOrchestrator) için fark görünmez.
 */
interface SatelliteDataSource {
    val source: SatelliteSource

    /**
     * Verilen bölge ve tarih aralığı için gerekli bantları indirir.
     * @param bbox sorgu bölgesi (polygon'un bounding box'ı)
     * @param dateRange sorgulanacak mutlak tarih aralığı (mevsim seçimi MainActivity'de yapılır)
     */
    suspend fun fetchScene(bbox: BoundingBox, dateRange: DateRange): SourceScene
}
