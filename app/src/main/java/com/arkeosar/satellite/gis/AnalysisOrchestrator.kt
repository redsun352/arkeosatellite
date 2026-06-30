package com.arkeosar.satellite.gis

import com.arkeosar.satellite.model.AnalysisResult
import com.arkeosar.satellite.model.AnomalyCell
import com.arkeosar.satellite.model.HeightmapGrid
import com.arkeosar.satellite.model.ScanPolygon
import com.arkeosar.satellite.model.SatelliteSource
import com.arkeosar.satellite.network.BandRaster
import com.arkeosar.satellite.network.DateRange
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

    companion object {
        /** 3D yüzey görselleştirmesi için downsample edilmiş grid boyutu (kare, NxN). */
        private const val HEIGHTMAP_GRID_SIZE = 96
    }

    suspend fun analyze(polygon: ScanPolygon, dateRange: DateRange): AnalysisResult = coroutineScope {
        val bbox = polygon.boundingBox()

        // Her kaynaktan paralel olarak veri çek - biri başarısız olursa diğerleri etkilenmesin
        // diye sonuçları Result<SourceScene> olarak topluyoruz. Hangi SatelliteSource'un
        // hangi sonucu/hatayı ürettiğini eşleştirebilmek için (sourceType, result) çifti tutuyoruz.
        val sceneResults = sources.map { dataSource ->
            async {
                dataSource.source to runCatching { dataSource.fetchScene(bbox, dateRange) }
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

        val (cells, heightmap) = computeAnomalyCells(successfulScenes, polygon)

        AnalysisResult(
            polygon = polygon,
            cells = cells,
            sourcesUsed = successfulScenes.map { it.source },
            failedSources = failures,
            heightmap = heightmap,
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Her piksel konumu için, polygon içindeyse ve mevcut bantlardan bir anomali
     * skoru hesaplanabiliyorsa bir AnomalyCell üretir.
     *
     * Skor mantığı (V1.1 - yerel sapma/z-score tabanlı):
     *  - Her bant (NDVI, LST) için önce TÜM rasterin ortalaması ve standart sapması hesaplanır.
     *  - Her pikselin skoru, o pikselin bölge ortalamasından KAÇ STANDART SAPMA uzakta
     *    olduğuna (z-score) göre belirlenir - MUTLAK bir "düşük NDVI = anomali" eşiği
     *    KULLANILMAZ, çünkü bu çorak/kurak arazilerde (NDVI genel olarak düşük) anlamsız
     *    sonuçlar üretir (bkz. zScoreToAnomalyScore dokümantasyonu).
     *  - Birden fazla kaynak varsa skorlar ortalanır (basit ensemble).
     */
    private fun computeAnomalyCells(scenes: List<SourceScene>, polygon: ScanPolygon): Pair<List<AnomalyCell>, HeightmapGrid> {
        // Polygon'u GoogleMap LatLng listesine çevir (PolyUtil bunu bekliyor)
        val polygonLatLngs = polygon.points.map { LatLng(it.lat, it.lng) }

        // Referans raster: ilk sahnenin ilk bandı, grid boyutunu (width/height) belirler.
        // Farklı kaynaklar farklı çözünürlükte dönebilir; V1'de basitleştirme olarak
        // sadece ilk sahnenin grid'i kullanılıyor, diğer kaynaklar aynı grid'e en yakın
        // örnekleme ile haritalanıyor (nearest-neighbor).
        val referenceRaster = scenes.first().bands.values.first()

        // Her sahne için istatistikleri (mean/std) BİR KERE, döngü dışında hesapla.
        // computeScoreAt her piksel için çağrılacağı için, istatistikleri piksel-piksel
        // yeniden hesaplamak O(n²) karmaşıklık yaratırdı - büyük polygonlarda uygulamayı
        // donduracak kadar yavaş olurdu. Önbelleklenen istatistikler bu fonksiyona parametre
        // olarak geçilir.
        val statsCache = buildStatsCache(scenes)

        // --- GEÇİŞ 1: Tam çözünürlükte AnomalyCell listesi (harita overlay'i için) ---
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

                val (score, contributingFilters) = computeScoreAt(scenes, referenceRaster, row, col, statsCache)
                if (contributingFilters.isNotEmpty()) {
                    cells.add(AnomalyCell(lat = lat, lng = lng, score = score, contributingFilters = contributingFilters))
                }
            }
        }

        // --- GEÇİŞ 2: 3D yüzey görselleştirmesi için AYRI, downsample edilmiş düzenli grid ---
        // Bu geçiş cells listesinden bağımsızdır - polygon dışı hücreler burada ATLANMAZ,
        // 0f (düz/sıfır yükseklik) olarak işaretlenir, çünkü OpenGL'de tutarlı bir üçgen mesh
        // inşa edebilmek için düzenli (her satır/sütun dolu) bir grid gerekir.
        //
        // PCA Veri Füzyonu filtresi (SurferFilters.pcaAnomalyFusion) için Sentinel-2'nin
        // NDVI ve NDWI bantları (varsa) HAM olarak da bu downsample grid boyutunda saklanır.
        val sentinelScene = scenes.firstOrNull { it.source == SatelliteSource.SENTINEL_2 }
        val ndviRaster = sentinelScene?.bands?.get("NDVI")
        val ndwiRaster = sentinelScene?.bands?.get("NDWI")
        val hasNdviNdwi = ndviRaster != null && ndwiRaster != null

        val heightmapScores = FloatArray(HEIGHTMAP_GRID_SIZE * HEIGHTMAP_GRID_SIZE)
        val rawNdviGrid = if (hasNdviNdwi) FloatArray(HEIGHTMAP_GRID_SIZE * HEIGHTMAP_GRID_SIZE) else null
        val rawNdwiGrid = if (hasNdviNdwi) FloatArray(HEIGHTMAP_GRID_SIZE * HEIGHTMAP_GRID_SIZE) else null

        for (gridRow in 0 until HEIGHTMAP_GRID_SIZE) {
            for (gridCol in 0 until HEIGHTMAP_GRID_SIZE) {
                val row = (gridRow.toDouble() / HEIGHTMAP_GRID_SIZE * referenceRaster.heightPx).toInt()
                    .coerceIn(0, referenceRaster.heightPx - 1)
                val col = (gridCol.toDouble() / HEIGHTMAP_GRID_SIZE * referenceRaster.widthPx).toInt()
                    .coerceIn(0, referenceRaster.widthPx - 1)

                val lat = referenceRaster.bbox.maxLat -
                    (row.toDouble() / referenceRaster.heightPx) * (referenceRaster.bbox.maxLat - referenceRaster.bbox.minLat)
                val lng = referenceRaster.bbox.minLng +
                    (col.toDouble() / referenceRaster.widthPx) * (referenceRaster.bbox.maxLng - referenceRaster.bbox.minLng)

                val insidePolygon = PolyUtil.containsLocation(LatLng(lat, lng), polygonLatLngs, true)
                val (score, _) = computeScoreAt(scenes, referenceRaster, row, col, statsCache)
                val gridIndex = gridRow * HEIGHTMAP_GRID_SIZE + gridCol
                heightmapScores[gridIndex] = if (insidePolygon) score.toFloat() else 0f

                if (hasNdviNdwi) {
                    val ndvi = sampleNearest(ndviRaster!!, referenceRaster, row, col) ?: 0f
                    val ndwi = sampleNearest(ndwiRaster!!, referenceRaster, row, col) ?: 0f
                    rawNdviGrid!![gridIndex] = ndvi
                    rawNdwiGrid!![gridIndex] = ndwi
                }
            }
        }
        val heightmap = HeightmapGrid(
            width = HEIGHTMAP_GRID_SIZE,
            height = HEIGHTMAP_GRID_SIZE,
            scores = heightmapScores,
            rawNdvi = rawNdviGrid,
            rawNdwi = rawNdwiGrid
        )

        return cells to heightmap
    }

    private data class BandStats(val mean: Double, val std: Double)
    private data class StatsKey(val source: SatelliteSource, val bandName: String)

    /** Her sahne/bant kombinasyonu için gerekli istatistikleri (mean+std) bir kere hesaplar. */
    private fun buildStatsCache(scenes: List<SourceScene>): Map<StatsKey, BandStats> {
        val cache = mutableMapOf<StatsKey, BandStats>()
        for (scene in scenes) {
            when (scene.source) {
                SatelliteSource.SENTINEL_2 -> {
                    scene.bands["NDVI"]?.let { raster ->
                        cache[StatsKey(scene.source, "NDVI")] = computeStats(raster.values.map { it.toDouble() })
                    }
                    scene.bands["NDWI"]?.let { raster ->
                        cache[StatsKey(scene.source, "NDWI")] = computeStats(raster.values.map { it.toDouble() })
                    }
                }
                SatelliteSource.LANDSAT_TIRS -> {
                    val lstRaster = scene.bands["LST"] ?: continue
                    cache[StatsKey(scene.source, "LST")] = computeStats(lstRaster.values.map { it.toDouble() })
                }
                SatelliteSource.PLANET -> {
                    val redRaster = scene.bands["RED"] ?: continue
                    val nirRaster = scene.bands["NIR"] ?: continue
                    val ndviValues = DoubleArray(redRaster.values.size)
                    for (i in redRaster.values.indices) {
                        val r = redRaster.values[i]
                        val n = nirRaster.values[i]
                        ndviValues[i] = if (n + r > 1e-6) ((n - r) / (n + r)).toDouble() else 0.0
                    }
                    cache[StatsKey(scene.source, "NDVI")] = computeStats(ndviValues.toList())
                }
                else -> { /* ASTER_TIR: henüz dahil değil */ }
            }
        }
        return cache
    }

    private fun computeStats(values: List<Double>): BandStats {
        val mean = values.average()
        val std = kotlin.math.sqrt(values.map { (it - mean) * (it - mean) }.average())
        return BandStats(mean, std)
    }

    private fun computeScoreAt(
        scenes: List<SourceScene>,
        referenceRaster: BandRaster,
        row: Int,
        col: Int,
        statsCache: Map<StatsKey, BandStats>
    ): Pair<Double, List<String>> {
        val scores = mutableListOf<Double>()
        val filters = mutableListOf<String>()

        for (scene in scenes) {
            when (scene.source) {
                SatelliteSource.SENTINEL_2 -> {
                    scene.bands["NDVI"]?.let { ndviRaster ->
                        val ndvi = sampleNearest(ndviRaster, referenceRaster, row, col)
                        val stats = statsCache[StatsKey(scene.source, "NDVI")]
                        if (ndvi != null && stats != null) {
                            zScoreToAnomalyScore(ndvi.toDouble(), stats)?.let {
                                scores.add(it)
                                filters.add("NDVI (Sentinel-2)")
                            }
                        }
                    }
                    scene.bands["NDWI"]?.let { ndwiRaster ->
                        val ndwi = sampleNearest(ndwiRaster, referenceRaster, row, col)
                        val stats = statsCache[StatsKey(scene.source, "NDWI")]
                        if (ndwi != null && stats != null) {
                            zScoreToAnomalyScore(ndwi.toDouble(), stats)?.let {
                                scores.add(it)
                                filters.add("NDWI/Nem Anomalisi (Sentinel-2)")
                            }
                        }
                    }
                }
                SatelliteSource.LANDSAT_TIRS -> {
                    val lstRaster = scene.bands["LST"] ?: continue
                    val lst = sampleNearest(lstRaster, referenceRaster, row, col) ?: continue
                    val stats = statsCache[StatsKey(scene.source, "LST")] ?: continue
                    val score = zScoreToAnomalyScore(lst.toDouble(), stats) ?: continue
                    scores.add(score)
                    filters.add("Termal Anomali (Landsat TIRS)")
                }
                SatelliteSource.PLANET -> {
                    // Planet ortho_analytic_4b_sr: ham DN (Digital Number) değerleri, kalibre
                    // edilmiş reflectance değil. NDVI = (NIR-RED)/(NIR+RED) oransal bir formül
                    // olduğu için DN ölçeğinde de matematiksel olarak doğru sonuç verir.
                    val redRaster = scene.bands["RED"] ?: continue
                    val nirRaster = scene.bands["NIR"] ?: continue
                    val red = sampleNearest(redRaster, referenceRaster, row, col) ?: continue
                    val nir = sampleNearest(nirRaster, referenceRaster, row, col) ?: continue
                    if (nir + red < 1e-6) continue // sıfıra bölme koruması
                    val ndvi = (nir - red) / (nir + red)
                    val stats = statsCache[StatsKey(scene.source, "NDVI")] ?: continue
                    val score = zScoreToAnomalyScore(ndvi.toDouble(), stats) ?: continue
                    scores.add(score)
                    filters.add("NDVI (Planet)")
                }
                else -> { /* ASTER_TIR: V1'de skor hesabına henüz dahil değil */ }
            }
        }

        if (scores.isEmpty()) return 0.0 to emptyList()
        return scores.average() to filters
    }

    /**
     * Ham değeri (NDVI veya LST), önceden hesaplanmış bölgesel istatistiklere göre
     * YEREL SAPMA (z-score) skoruna çevirir - MUTLAK bir eşik DEĞİL.
     *
     * Bu fark kritiktir: çorak/kurak bir arazide (örn. step, yarı çöl) NDVI zaten her yerde
     * düşüktür - mutlak eşik kullanılırsa neredeyse TÜM pikseller "anomali" görünür, bu da
     * anlamsız/rastgele dağılmış sonuçlara yol açar. Z-score mantığı bunun yerine "bu piksel,
     * çevresindeki bölgenin tipik değerinden ne kadar sapıyor" sorusuna cevap verir - gömülü
     * bir yapının üstündeki toprak, çevresinden farklı bitki örtüsü/termal davranış gösterirse
     * bu YEREL sapma olarak yakalanır, genel arazi kuruluğundan/sıcaklığından bağımsız olarak.
     */
    private fun zScoreToAnomalyScore(value: Double, stats: BandStats): Double? {
        if (stats.std < 1e-6) return null // raster tamamen düz (varyans yok), anlamlı sapma hesaplanamaz
        val zScore = kotlin.math.abs((value - stats.mean) / stats.std)
        return (zScore / 3.0).coerceIn(0.0, 1.0) // 3-sigma'da skor 1.0'a yaklaşır
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
