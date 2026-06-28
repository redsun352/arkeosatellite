package com.arkeosar.satellite.network.planet

import com.arkeosar.satellite.gis.GeoTiffDecoder
import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.model.SatelliteSource
import com.arkeosar.satellite.network.DateRange
import com.arkeosar.satellite.network.RetrofitFactory
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.SecureConfig
import com.arkeosar.satellite.network.SourceScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.create

/**
 * Planet Data API üzerinden PSScene (PlanetScope) görüntüsü bulur ve indirir.
 *
 * Akış:
 *  1. quick-search: bbox + zaman aralığı + bulut filtresi ile en uygun sahneyi bul
 *  2. En düşük bulutlu sahnenin asset listesini al
 *  3. İlgili asset_type'ı aktive et (activate)
 *  4. Asset "active" olana kadar polling yap (status alanı)
 *  5. location URL'inden gerçek raster dosyasını indir (GeoTIFF)
 *
 * Tarih aralığı, çağıran kodun (MainActivity'deki mevsim seçici) belirlediği MUTLAK
 * bir aralıktır - "son N gün" değil, çünkü crop-mark tespiti mevsime bağımlıdır.
 *
 * NOT: Planet'ın 4-bant (BGRN) ürünlerinde SWIR bandı yoktur, bu yüzden NDWI (NIR-SWIR)
 * Planet kaynağından hesaplanamaz - sadece NDVI (RED/NIR) hesaplanır.
 */
class PlanetDataSource : SatelliteDataSource {

    override val source = SatelliteSource.PLANET

    private val api: PlanetDataApi by lazy {
        RetrofitFactory.create("https://api.planet.com/").create()
    }

    private fun authHeader() = "api-key ${SecureConfig.Planet.apiKey}"

    override suspend fun fetchScene(bbox: BoundingBox, dateRange: DateRange): SourceScene = withContext(Dispatchers.IO) {
        // Planet ISO-8601 datetime bekliyor - LocalDate'leri gün başı/gün sonu olarak çeviriyoruz.
        val fromIso = dateRange.from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()
        val toIso = dateRange.to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()

        val geometry = mapOf(
            "type" to "Polygon",
            "coordinates" to listOf(
                listOf(
                    listOf(bbox.minLng, bbox.minLat),
                    listOf(bbox.maxLng, bbox.minLat),
                    listOf(bbox.maxLng, bbox.maxLat),
                    listOf(bbox.minLng, bbox.maxLat),
                    listOf(bbox.minLng, bbox.minLat)
                )
            )
        )

        val searchRequest = PlanetSearchRequest(
            item_types = listOf(SecureConfig.Planet.itemTypes),
            filter = PlanetFilter(
                config = listOf(
                    mapOf(
                        "type" to "GeometryFilter",
                        "field_name" to "geometry",
                        "config" to geometry
                    ),
                    mapOf(
                        "type" to "DateRangeFilter",
                        "field_name" to "acquired",
                        "config" to mapOf("gte" to fromIso, "lte" to toIso)
                    ),
                    mapOf(
                        "type" to "RangeFilter",
                        "field_name" to "cloud_cover",
                        "config" to mapOf("lte" to SecureConfig.Planet.cloudMax)
                    )
                )
            )
        )

        val searchResponse = api.quickSearch(authHeader(), searchRequest)
        val bestFeature = searchResponse.features.minByOrNull { it.properties.cloud_cover ?: 1.0 }
            ?: throw IllegalStateException("Planet: bölge/tarih için uygun sahne bulunamadı")

        val assets = api.getAssets(bestFeature._links.assets, authHeader())
        val targetAsset = assets["ortho_analytic_4b_sr"]
            ?: throw IllegalStateException("Planet: 'ortho_analytic_4b_sr' asset'i bu sahnede mevcut değil")

        val activeAsset = activateAndWait(targetAsset)
        val downloadUrl = activeAsset._links.self
            ?: throw IllegalStateException("Planet: aktif asset için indirme linki bulunamadı")

        val response = api.downloadAsset(downloadUrl, authHeader())
        if (!response.isSuccessful) {
            throw IllegalStateException("Planet indirme hatası: ${response.code()}")
        }
        val tiffBytes = response.body()?.bytes()
            ?: throw IllegalStateException("Planet: boş yanıt")

        // ortho_analytic_4b_sr çok bantlıdır - PlanetScope Surface Reflectance ürünleri
        // standart olarak BGRN (Blue, Green, Red, Near-Infrared) sırasındadır.
        val bandRasters = GeoTiffDecoder.decodeMultiBand(tiffBytes, bbox, listOf("BLUE", "GREEN", "RED", "NIR"))

        SourceScene(
            source = source,
            bands = bandRasters,
            acquiredEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun activateAndWait(
        asset: PlanetAsset,
        maxAttempts: Int = 12,
        pollIntervalMs: Long = 5000
    ): PlanetAsset {
        if (asset.status == "active") return asset

        val selfUrl = asset._links.self
            ?: throw IllegalStateException("Planet: asset'in self-link'i yok, durum sorgulanamıyor")

        api.activateAsset(asset._links.activate, authHeader())

        repeat(maxAttempts) {
            delay(pollIntervalMs)
            val current = api.getAssetStatus(selfUrl, authHeader())
            if (current.status == "active") return current
        }
        throw IllegalStateException("Planet: asset aktivasyonu zaman aşımına uğradı (${maxAttempts * pollIntervalMs}ms)")
    }
}
