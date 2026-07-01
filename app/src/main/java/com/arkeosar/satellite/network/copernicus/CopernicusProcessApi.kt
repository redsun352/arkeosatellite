package com.arkeosar.satellite.network.copernicus

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Sentinel Hub Process API isteği. "evalscript" alanı, hangi bantların ve
 * hangi formatta isteneceğini tarif eden küçük bir JS programıdır - GEE'nin
 * expression-graph'ından farklı olarak BU FORMAT SABİT VE ELLE YAZILABİLİR,
 * Sentinel Hub'ın kendi dokümantasyonunda örnekleri var.
 */
data class ProcessApiRequest(
    val input: ProcessInput,
    val output: ProcessOutput,
    val evalscript: String
)

data class ProcessInput(
    val bounds: ProcessBounds,
    val data: List<ProcessDataSource>
)

data class ProcessBounds(
    val bbox: List<Double>, // [minLng, minLat, maxLng, maxLat]
    val properties: ProcessCrs = ProcessCrs()
)

data class ProcessCrs(val crs: String = "http://www.opengis.net/def/crs/EPSG/0/4326")

data class ProcessDataSource(
    val type: String, // "sentinel-2-l2a"
    val dataFilter: ProcessDataFilter
)

data class ProcessDataFilter(
    val timeRange: ProcessTimeRange,
    val maxCloudCoverage: Int
)

data class ProcessTimeRange(val from: String, val to: String) // ISO-8601

data class ProcessOutput(
    val width: Int,
    val height: Int,
    val responses: List<ProcessResponseFormat> = listOf(ProcessResponseFormat())
)

data class ProcessResponseFormat(
    val identifier: String = "default",
    val format: ProcessFormat = ProcessFormat()
)

data class ProcessFormat(val type: String = "image/tiff")

interface CopernicusProcessApi {
    @POST("api/v1/process")
    suspend fun process(
        @Header("Authorization") bearerToken: String,
        @Body request: ProcessApiRequest
    ): Response<okhttp3.ResponseBody>

    @POST("api/v1/process")
    suspend fun processDem(
        @Header("Authorization") bearerToken: String,
        @Body request: DemProcessRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * NDVI ve NDWI'yi (NIR-SWIR/Gao versiyonu) BİRLİKTE, 2 bantlı tek bir GeoTIFF olarak
 * hesaplayan evalscript.
 *
 * NDVI = (NIR-RED)/(NIR+RED) -> bitki örtüsü sağlığı/yoğunluğu
 * NDWI (Gao 1996, NIR-SWIR) = (NIR-SWIR)/(NIR+SWIR) -> bitki/toprak nem içeriği
 *
 * NOT: Bu NDWI formülü "McFeeters" (Green-NIR, açık su gövdesi tespiti) DEĞİLDİR -
 * burada kullanılan Gao versiyonu (NIR-SWIR) toprak/bitki nem stresini ölçer, ki
 * arkeolojik crop-mark tespitinde aranan sinyal budur (gömülü yapı üstündeki toprağın
 * farklı drenaj/nem tutma davranışı). Sentinel-2'de SWIR bandı B11'dir (1610nm).
 */
object Evalscripts {
    val ndvi = """
        //VERSION=3
        function setup() {
          return {
            input: ["B04", "B08"],
            output: { bands: 1, sampleType: "FLOAT32" }
          };
        }
        function evaluatePixel(sample) {
          let ndvi = (sample.B08 - sample.B04) / (sample.B08 + sample.B04);
          return [ndvi];
        }
    """.trimIndent()

    /** NDVI (bant 0) ve NDWI/NIR-SWIR (bant 1) - chunky/interleaved 2 bant GeoTIFF. */
    val ndviAndNdwi = """
        //VERSION=3
        function setup() {
          return {
            input: ["B04", "B08", "B11"],
            output: { bands: 2, sampleType: "FLOAT32" }
          };
        }
        function evaluatePixel(sample) {
          let ndvi = (sample.B08 - sample.B04) / (sample.B08 + sample.B04);
          let ndwi = (sample.B08 - sample.B11) / (sample.B08 + sample.B11);
          return [ndvi, ndwi];
        }
    """.trimIndent()

    /**
     * 4 bantlı mineral analiz evalscript:
     *  Bant 0: NDVI  = (B08-B04)/(B08+B04)  — bitki/toprak aktivitesi
     *  Bant 1: NDWI  = (B08-B11)/(B08+B11)  — nem/drenaj
     *  Bant 2: IOI   = B04/B02               — Iron Oxide Index (demir oksit göstergesi)
     *  Bant 3: CMR   = B11/B12               — Clay Mineral Ratio (kil/alterasyon göstergesi)
     *
     * Bilimsel referans:
     *  - IOI (B4/B2): Van der Meer et al. (2014), demir oksit/hidroksit haritalama için
     *    standart Sentinel-2 bant oranı. Demir oksit varlığında B04(kırmızı) reflektansı
     *    artar, B02(mavi) azalır → oran yükselir. Metal objelerin çevresindeki toprağın
     *    zamanla kimyasal değişimine (paslanma/oksitlenme) duyarlıdır.
     *  - CMR (B11/B12): Van der Meer et al. (2014), kil mineral ve hidrotermal alterasyon
     *    haritalama. Kil mineralleri B11'de absorpsiyon özelliği gösterir → oran yükselir.
     *
     * Bu bantlar B02 ve B12 gerektirdiğinden AYRI bir API çağrısı gerektirir - mevcut
     * ndviAndNdwi çağrısıyla AYNI anda gönderilmez (Sentinel Hub paralel istek sınırı
     * nedeniyle ayrı bir zaman diliminde çekilir ve isteğe bağlı bir ek analiz adımı
     * olarak sunulur).
     */
    val ndviNdwiIoiCmr = """
        //VERSION=3
        function setup() {
          return {
            input: ["B02", "B04", "B08", "B11", "B12"],
            output: { bands: 4, sampleType: "FLOAT32" }
          };
        }
        function evaluatePixel(sample) {
          let ndvi = (sample.B08 - sample.B04) / (sample.B08 + sample.B04);
          let ndwi = (sample.B08 - sample.B11) / (sample.B08 + sample.B11);
          let ioi  = sample.B04 / Math.max(sample.B02, 0.0001);
          let cmr  = sample.B11 / Math.max(sample.B12, 0.0001);
          return [ndvi, ndwi, ioi, cmr];
        }
    """.trimIndent()

    /**
     * Copernicus DEM GLO-30 evalscript — 30m çözünürlükte sayısal yükseklik modeli.
     * Tarih bağımsızdır (statik dataset, TanDEM-X 2011-2015 arası veri).
     * Sentinel Hub'da input type "dem" (Sentinel-2 değil) kullanılır.
     *
     * NOT: DEM değerleri negatif olabilir (deniz altı), +12000 offset eklenerek
     * UINT16 olarak döndürülür — Kotlin tarafında -12000 çıkarılarak gerçek
     * yüksekliğe dönüştürülür.
     */
    val dem = """
        //VERSION=3
        function setup() {
          return {
            input: ["DEM"],
            output: { bands: 1, sampleType: "FLOAT32" }
          };
        }
        function evaluatePixel(sample) {
          return [sample.DEM];
        }
    """.trimIndent()
}

/** DEM verisi için ayrı bir ProcessDataSource tanımı — tip "dem" olarak değişir. */
data class DemProcessRequest(
    val input: DemProcessInput,
    val output: ProcessOutput,
    val evalscript: String
)

data class DemProcessInput(
    val bounds: ProcessBounds,
    val data: List<DemDataSource>
)

data class DemDataSource(
    val type: String = "dem",
    val dataFilter: DemDataFilter = DemDataFilter()
)

data class DemDataFilter(
    val demInstance: String = "COPERNICUS_30"
)
