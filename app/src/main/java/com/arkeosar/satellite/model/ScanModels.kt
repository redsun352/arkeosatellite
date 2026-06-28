package com.arkeosar.satellite.model

/**
 * Kullanıcının haritada çizdiği taranacak alanı temsil eder.
 * Noktalar harita dokunuşlarıyla sırayla eklenir; ilk noktaya
 * tekrar dokunulduğunda polygon kapatılır.
 */
data class ScanPolygon(
    val points: List<LatLngPoint>
) {
    val isClosed: Boolean get() = points.size >= 3

    /**
     * Polygonu kapsayan dikdörtgen (bounding box) - uydu API'lerine
     * bölge sorgusu yaparken kullanılır. API'ler genelde dikdörtgen
     * sorgular, polygon maskesi indirilen rasterin üzerine sonradan uygulanır.
     */
    fun boundingBox(): BoundingBox {
        require(points.isNotEmpty()) { "Boş polygon için bounding box hesaplanamaz" }
        val lats = points.map { it.lat }
        val lngs = points.map { it.lng }
        return BoundingBox(
            minLat = lats.min(),
            maxLat = lats.max(),
            minLng = lngs.min(),
            maxLng = lngs.max()
        )
    }

    /** Yaklaşık alan (km²) - çok büyük bölgeleri engellemek için kaba bir kontrol. */
    fun approxAreaKm2(): Double {
        val bbox = boundingBox()
        val latKm = (bbox.maxLat - bbox.minLat) * 111.0
        val avgLat = (bbox.maxLat + bbox.minLat) / 2.0
        val lngKm = (bbox.maxLng - bbox.minLng) * 111.0 * Math.cos(Math.toRadians(avgLat))
        return latKm * lngKm
    }
}

data class LatLngPoint(val lat: Double, val lng: Double)

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
)

/** Kullanılabilir uydu veri kaynakları - ArkeoSAR Pro'daki kaynak setiyle aynı. */
enum class SatelliteSource(val displayName: String) {
    SENTINEL_2("Sentinel-2 (Multispektral)"),
    LANDSAT_TIRS("Landsat TIRS (Termal)"),
    ASTER_TIR("ASTER (Termal)"),
    PLANET("Planet PSScene"),
}

/** Bir piksel/hücre için hesaplanan anomali skoru ve hangi filtrelerden geldiği. */
data class AnomalyCell(
    val lat: Double,
    val lng: Double,
    val score: Double, // 0.0 (anomali yok) - 1.0 (yüksek anomali)
    val contributingFilters: List<String>
)

data class AnalysisResult(
    val polygon: ScanPolygon,
    val cells: List<AnomalyCell>,
    val sourcesUsed: List<SatelliteSource>,
    val failedSources: List<Pair<String, String>> = emptyList(), // (kaynak adı, hata mesajı)
    val generatedAtEpochMs: Long
)
