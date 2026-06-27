package com.arkeosar.satellite.network.copernicus

import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.model.SatelliteSource
import com.arkeosar.satellite.network.BandRaster
import com.arkeosar.satellite.network.RetrofitFactory
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.SecureConfig
import com.arkeosar.satellite.network.SourceScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.create
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Copernicus Data Space / Sentinel Hub Process API üzerinden Sentinel-2 NDVI verisi çeker.
 *
 * Akış:
 *  1. client_id/client_secret ile OAuth2 token al (1 saat geçerli, cache'lenir)
 *  2. Process API'ye evalscript + bbox + zaman aralığı ile POST isteği gönder
 *  3. Dönen GeoTIFF'i (tek bant, FLOAT32) ayrıştır -> BandRaster
 *
 * NOT: GeoTIFF ayrıştırma burada basitleştirilmiş - gerçek implementasyonda
 * bir TIFF decoder (örn. küçük bir libtiff bağlama veya kendi yazılan basit
 * okuyucu) gerekir. Bu sınıf, ağ/auth katmanını eksiksiz kurar; raster
 * decode kısmı GeoTiffDecoder'a devredilir (ayrı adımda yazılacak).
 */
class CopernicusDataSource : SatelliteDataSource {

    override val source = SatelliteSource.SENTINEL_2

    private val authApi: CopernicusAuthApi by lazy {
        RetrofitFactory.create("https://identity.dataspace.copernicus.eu/").create()
    }

    private val processApi: CopernicusProcessApi by lazy {
        RetrofitFactory.create("https://sh.dataspace.copernicus.eu/").create()
    }

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtEpochMs: Long = 0L

    private suspend fun getToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.let { token ->
            if (now < tokenExpiresAtEpochMs - 30_000) return@withContext token // 30s güvenlik payı
        }
        val response = authApi.fetchToken(
            clientId = SecureConfig.Copernicus.clientId,
            clientSecret = SecureConfig.Copernicus.clientSecret
        )
        cachedToken = response.access_token
        tokenExpiresAtEpochMs = now + TimeUnit.SECONDS.toMillis(response.expires_in.toLong())
        response.access_token
    }

    override suspend fun fetchScene(bbox: BoundingBox, maxAgeDays: Int): SourceScene = withContext(Dispatchers.IO) {
        val token = getToken()

        val now = Instant.now()
        val from = now.minus(maxAgeDays.toLong(), ChronoUnit.DAYS)

        val request = ProcessApiRequest(
            input = ProcessInput(
                bounds = ProcessBounds(bbox = listOf(bbox.minLng, bbox.minLat, bbox.maxLng, bbox.maxLat)),
                data = listOf(
                    ProcessDataSource(
                        type = "sentinel-2-l2a",
                        dataFilter = ProcessDataFilter(
                            timeRange = ProcessTimeRange(from = from.toString(), to = now.toString()),
                            maxCloudCoverage = SecureConfig.Copernicus.cloudMax
                        )
                    )
                )
            ),
            output = ProcessOutput(width = 512, height = 512),
            evalscript = Evalscripts.ndvi
        )

        val response = processApi.process(bearerToken = "Bearer $token", request = request)
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Copernicus Process API hatası: ${response.code()} ${response.errorBody()?.string()}"
            )
        }

        val tiffBytes = response.body()?.bytes()
            ?: throw IllegalStateException("Copernicus Process API boş yanıt döndü")

        val raster = com.arkeosar.satellite.gis.GeoTiffDecoder.decodeSingleBand(
            tiffBytes = tiffBytes,
            bbox = bbox,
            bandName = "NDVI"
        )

        SourceScene(
            source = source,
            bands = mapOf("NDVI" to raster),
            acquiredEpochMs = System.currentTimeMillis()
        )
    }
}
