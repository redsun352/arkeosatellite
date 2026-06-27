package com.arkeosar.satellite.gis

import com.arkeosar.satellite.model.AnalysisResult
import com.arkeosar.satellite.model.AnomalyCell
import com.arkeosar.satellite.model.ScanPolygon
import com.arkeosar.satellite.model.SatelliteSource
import com.arkeosar.satellite.network.BandRaster
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.SourceScene
import com.google.maps.android.PolyUtil
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Seçilen polygon için bir veya daha fazla uydu kaynağından veri çeker,
 * yeraltı/anomali filtrelerini uygular ve polygon SINIRLARI İÇİNDEKİ
 * hücreler için tek bir AnomalyCell listesi üretir.
 *
 * Polygon dışı hücreler (PolyUtil.containsLocation ile test edilir) sonuca dahil edilmez -
 * bu, kullanıcının "polygon içi taranır dışı taranmaz" isteğinin uygulanma noktasıdır.
 */
class AnalysisOrchestrator(private val sources: List<SatelliteDataSource>) {

    suspend fun analyze(polygon: ScanPolygon): AnalysisResult = coroutineScope {
        val bbox = polygon.boundingBox()

        // Her kaynaktan paralel olarak veri çek - biri başarısız olursa diğerleri etkilenmesin
        // diye sonuçları Result<SourceScene> olarak topluyoruz. Hangi SatelliteSource'un
        // hangi sonucu/hatayı ürettiğini eşleştirebilmek için (sourceType, result) çifti tutuyoruz.
        val sceneResults = sources.map { dataSource ->
            async {
                dataSource.source to runCatching { dataSource.fetchScene(bbox) }
            }
        }.awaitAll()

        val successfulScenes = sceneResults.mapNotNull { (_, result) -> result.getOrNull() }
        val failures = sceneResults.mapNotNull { (sourceType, result) ->
            result.exceptionOrNull()?.let { ex -> sourceType.displayName to (ex.message ?: ex.javaClass.simpleName) }
        }

        if (successfulScenes.isEmpty()) {
            val detail = failures.joinToString("; ") { (name, msg) -> "$name: $msg" }
            throw IllegalStateException(
                "Hiçbir uydu kaynağından veri alınamadı (${sources.size} kaynak denendi, hepsi başarısız oldu) -> $detail"
            )
        }

        val cells = computeAnomalyCells(successfulScenes, polygon)

        AnalysisResult(
            polygon = polygon,
            cells = cells,
            sourcesUsed = successfulScenes.map { it.source },
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Her piksel konumu için, polygon içindeyse ve mevcut bantlardan bir anomali
     * skoru hesaplanabiliyorsa bir AnomalyCell üretir.
     *
     * Basit skor mantığı (V1):
     *  - NDVI bandı varsa: düşük NDVI (bitki örtüsü stresi/yokluğu) potansiyel
     *    yeraltı yapısı sinyali olarak yorumlanır (klasik arkeolojik crop-mark mantığı,
     *    ArkeoSAR Pro'daki yaklaşımla aynı). Skor = clamp(1 - (ndvi+1)/2, 0, 1)
     *  - LST (yüzey sıcaklığı) bandı varsa: yerel ortalamadan anlamlı sapma
     *    (örn. gömülü duvar/taş yapı üstündeki termal atalet farkı) skor olarak eklenir.
     *  - Birden fazla kaynak varsa skorlar ortalanır (basit ensemble).
     */
    private fun computeAnomalyCells(scenes: List<SourceScene>, polygon: ScanPolygon): List<AnomalyCell> {
        // Polygon'u GoogleMap LatLng listesine çevir (PolyUtil bunu bekliyor)
        val polygonLatLngs = polygon.points.map { LatLng(it.lat, it.lng) }

        // Referans raster: ilk sahnenin ilk bandı, grid boyutunu (width/height) belirler.
        // Farklı kaynaklar farklı çözünürlükte dönebilir; V1'de basitleştirme olarak
        // sadece ilk sahnenin grid'i kullanılıyor, diğer kaynaklar aynı grid'e en yakın
        // örnekleme ile haritalanıyor (nearest-neighbor).
        val referenceRaster = scenes.first().bands.values.first()

        val cells = mutableListOf<AnomalyCell>()

        for (row in 0 until referenceRaster.heightPx) {
            for (col in 0 until referenceRaster.widthPx) {
                val lat = referenceRaster.bbox.maxLat -
                    (row.toDouble() / referenceRaster.heightPx) * (referenceRaster.bbox.maxLat - referenceRaster.bbox.minLat)
                val lng = referenceRaster.bbox.minLng +
                    (col.toDouble() / referenceRaster.widthPx) * (referenceRaster.bbox.maxLng - referenceRaster.bbox.minLng)

                val point = LatLng(lat, lng)
                if (!PolyUtil.containsLocation(point, polygonLatLngs, true)) {
                    continue // polygon dışı - kullanıcı isteği: dışı taranmaz/analiz edilmez
                }

                val (score, contributingFilters) = computeScoreAt(scenes, referenceRaster, row, col)
                if (contributingFilters.isNotEmpty()) {
                    cells.add(AnomalyCell(lat = lat, lng = lng, score = score, contributingFilters = contributingFilters))
                }
            }
        }

        return cells
    }

    private fun computeScoreAt(
        scenes: List<SourceScene>,
        referenceRaster: BandRaster,
        row: Int,
        col: Int
    ): Pair<Double, List<String>> {
        val scores = mutableListOf<Double>()
        val filters = mutableListOf<String>()

        for (scene in scenes) {
            when (scene.source) {
                SatelliteSource.SENTINEL_2 -> {
                    val ndviRaster = scene.bands["NDVI"] ?: continue
                    val ndvi = sampleNearest(ndviRaster, referenceRaster, row, col) ?: continue
                    // NDVI [-1,1] -> düşük bitki örtüsü = yüksek anomali skoru ihtimali
                    val score = (1.0 - ((ndvi + 1.0) / 2.0)).coerceIn(0.0, 1.0)
                    scores.add(score)
                    filters.add("NDVI (Sentinel-2)")
                }
                SatelliteSource.LANDSAT_TIRS -> {
                    val lstRaster = scene.bands["LST"] ?: continue
                    val lst = sampleNearest(lstRaster, referenceRaster, row, col) ?: continue
                    val meanLst = lstRaster.values.average()
                    val stdLst = kotlin.math.sqrt(lstRaster.values.map { (it - meanLst) * (it - meanLst) }.average())
                    if (stdLst < 1e-6) continue
                    val zScore = kotlin.math.abs((lst - meanLst) / stdLst)
                    val score = (zScore / 3.0).coerceIn(0.0, 1.0) // 3-sigma'da skor 1.0'a yaklaşır
                    scores.add(score)
                    filters.add("Termal Anomali (Landsat TIRS)")
                }
                else -> { /* PLANET, ASTER_TIR: V1'de skor hesabına henüz dahil değil */ }
            }
        }

        if (scores.isEmpty()) return 0.0 to emptyList()
        return scores.average() to filters
    }

    /** referenceRaster'daki (row,col) konumunu sourceRaster grid'inde en yakın hücreye haritalar. */
    private fun sampleNearest(sourceRaster: BandRaster, referenceRaster: BandRaster, row: Int, col: Int): Float? {
        if (sourceRaster.widthPx == referenceRaster.widthPx && sourceRaster.heightPx == referenceRaster.heightPx) {
            return sourceRaster.valueAt(row, col)
        }
        val sourceRow = (row.toDouble() / referenceRaster.heightPx * sourceRaster.heightPx).toInt()
            .coerceIn(0, sourceRaster.heightPx - 1)
        val sourceCol = (col.toDouble() / referenceRaster.widthPx * sourceRaster.widthPx).toInt()
            .coerceIn(0, sourceRaster.widthPx - 1)
        return sourceRaster.valueAt(sourceRow, sourceCol)
    }
}
