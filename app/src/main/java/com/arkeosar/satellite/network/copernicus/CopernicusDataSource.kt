package com.arkeosar.satellite.network.copernicus

import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.model.SatelliteSource
import com.arkeosar.satellite.network.DateRange
import com.arkeosar.satellite.network.RetrofitFactory
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.SecureConfig
import com.arkeosar.satellite.network.SourceScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * Copernicus Data Space / Sentinel Hub Process API üzerinden Sentinel-2 NDVI+NDWI+IOI+CMR
 * verisi çeker.
 *
 * Akış:
 *  1. client_id/client_secret ile OAuth2 token al (1 saat geçerli, cache'lenir)
 *  2. Process API'ye evalscript + bbox + zaman aralığı ile POST isteği gönder
 *  3. Dönen 4-bantlı GeoTIFF'i (NDVI + NDWI + IOI + CMR) ayrıştır -> BandRaster'lar
 *
 * 4 bant:
 *  - NDVI: bitki/toprak aktivitesi
 *  - NDWI: nem/drenaj (crop-mark tespiti)
 *  - IOI (Iron Oxide Index = B04/B02): demir oksit göstergesi - metal kimyasal izi
 *  - CMR (Clay Mineral Ratio = B11/B12): kil mineral/alterasyon göstergesi
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

    override suspend fun fetchScene(bbox: BoundingBox, dateRange: DateRange): SourceScene = withContext(Dispatchers.IO) {
        val token = getToken()

        // Sentinel Hub ISO-8601 datetime bekliyor - LocalDate'leri gün başı/gün sonu olarak çeviriyoruz.
        val fromIso = dateRange.from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()
        val toIso = dateRange.to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()

        val request = ProcessApiRequest(
            input = ProcessInput(
                bounds = ProcessBounds(bbox = listOf(bbox.minLng, bbox.minLat, bbox.maxLng, bbox.maxLat)),
                data = listOf(
                    ProcessDataSource(
                        type = "sentinel-2-l2a",
                        dataFilter = ProcessDataFilter(
                            timeRange = ProcessTimeRange(from = fromIso, to = toIso),
                            maxCloudCoverage = SecureConfig.Copernicus.cloudMax
                        )
                    )
                )
            ),
            output = ProcessOutput(width = 512, height = 512),
            evalscript = Evalscripts.ndviNdwiIoiCmr
        )

        val response = processApi.process(bearerToken = "Bearer $token", request = request)
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Copernicus Process API hatası: ${response.code()} ${response.errorBody()?.string()}"
            )
        }

        val tiffBytes = response.body()?.bytes()
            ?: throw IllegalStateException("Copernicus Process API boş yanıt döndü")

        val bandRasters = com.arkeosar.satellite.gis.GeoTiffDecoder.decodeMultiBand(
            tiffBytes = tiffBytes,
            bbox = bbox,
            bandNames = listOf("NDVI", "NDWI", "IOI", "CMR")
        )

        // DEM verisini ayrı bir istek olarak çek (tip "dem", tarih bağımsız).
        val demRaster = try {
            val demRequest = DemProcessRequest(
                input = DemProcessInput(
                    bounds = ProcessBounds(bbox = listOf(bbox.minLng, bbox.minLat, bbox.maxLng, bbox.maxLat)),
                    data = listOf(DemDataSource())
                ),
                output = ProcessOutput(width = 512, height = 512),
                evalscript = Evalscripts.dem
            )
            val demResponse = processApi.processDem(bearerToken = "Bearer $token", request = demRequest)
            if (demResponse.isSuccessful) {
                val demBytes = demResponse.body()?.bytes()
                if (demBytes != null) {
                    com.arkeosar.satellite.gis.GeoTiffDecoder.decodeSingleBand(demBytes, bbox, "DEM")
                } else null
            } else null
        } catch (e: Exception) {
            null // DEM başarısız olursa sessizce atla, ana analiz etkilenmesin
        }

        val allBands = bandRasters.toMutableMap()
        if (demRaster != null) allBands["DEM"] = demRaster

        SourceScene(
            source = source,
            bands = allBands,
            acquiredEpochMs = System.currentTimeMillis()
        )
    }
}
