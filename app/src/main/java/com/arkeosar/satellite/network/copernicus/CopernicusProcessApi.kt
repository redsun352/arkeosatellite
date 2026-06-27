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
}

/** NDVI hesaplayan, GeoTIFF (float32, tek bant) döndüren standart evalscript. */
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
}
