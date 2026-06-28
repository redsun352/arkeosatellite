package com.arkeosar.satellite.network.usgs

import com.arkeosar.satellite.gis.GeoTiffDecoder
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

/**
 * USGS M2M API üzerinden Landsat Collection 2 Level-2 (TIRS termal bant içerir) sahnesi bulur ve indirir.
 *
 * Akış:
 *  1. login-token: kullanıcı adı + application token ile X-Auth-Token al
 *  2. scene-search: bbox + tarih + bulut filtresiyle en uygun sahneyi bul
 *  3. download-options: o sahne için indirilebilir ürünleri listele
 *  4. download-request: seçilen ürün için gerçek indirme URL'ini al
 *  5. URL'den GeoTIFF indir ve decode et
 *
 * Dataset adı "landsat_ot_c2_l2" (Landsat 8/9 OLI/TIRS Collection 2 Level-2) kullanılıyor -
 * bu, termal bant (ST_B10, yüzey sıcaklığı) içeren standart üründür. Tarih aralığı artık
 * mutlak bir aralıktır (mevsim seçici - bkz. MainActivity).
 */
class UsgsDataSource : SatelliteDataSource {

    override val source = SatelliteSource.LANDSAT_TIRS

    companion object {
        private const val DATASET_NAME = "landsat_ot_c2_l2"
        private const val THERMAL_PRODUCT_NAME = "Land Surface Temperature"
    }

    private val api: UsgsM2mApi by lazy {
        RetrofitFactory.create("https://m2m.cr.usgs.gov/api/api/json/stable/").create()
    }

    @Volatile private var cachedAuthToken: String? = null

    private suspend fun getAuthToken(): String = withContext(Dispatchers.IO) {
        cachedAuthToken?.let { return@withContext it }

        val response = callWithDetailedError("login-token") {
            api.loginToken(LoginTokenRequest(username = SecureConfig.Usgs.user, token = SecureConfig.Usgs.m2mToken))
        }
        if (response.errorCode != null) {
            throw IllegalStateException("USGS login-token hatası: ${response.errorCode} - ${response.errorMessage}")
        }
        val token = response.data ?: throw IllegalStateException("USGS login-token: boş token döndü")
        cachedAuthToken = token
        token
    }

    /**
     * Retrofit'in suspend fonksiyonları, 2xx olmayan HTTP yanıtlarını otomatik olarak
     * retrofit2.HttpException'a çevirir - bu exception'ın varsayılan mesajı sadece
     * "HTTP 403 Forbidden" gibi kısa bir özet verir, sunucunun gerçek hata body'sini
     * (genelde errorCode/errorMessage içeren JSON) GÖSTERMEZ. Bu sarmalayıcı, hangi
     * adımda hata olduğunu ve sunucunun gerçek yanıt body'sini açıkça raporlar.
     */
    private suspend fun <T> callWithDetailedError(stepName: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            throw IllegalStateException("USGS $stepName hatası: HTTP ${e.code()} - ${errorBody ?: e.message()}")
        }
    }

    override suspend fun fetchScene(bbox: BoundingBox, dateRange: DateRange): SourceScene = withContext(Dispatchers.IO) {
        val authToken = getAuthToken()

        // USGS scene-search "YYYY-MM-DD" formatı bekliyor (LocalDate.toString() zaten bu formatı üretir - ISO_LOCAL_DATE).
        val searchRequest = SceneSearchRequest(
            datasetName = DATASET_NAME,
            sceneFilter = SceneFilter(
                spatialFilter = SpatialFilter(
                    lowerLeft = LatLng(latitude = bbox.minLat, longitude = bbox.minLng),
                    upperRight = LatLng(latitude = bbox.maxLat, longitude = bbox.maxLng)
                ),
                acquisitionFilter = AcquisitionFilter(start = dateRange.from.toString(), end = dateRange.to.toString()),
                cloudCoverFilter = CloudCoverFilter(min = 0, max = 40, includeUnknown = true)
            ),
            maxResults = 5
        )

        val searchResponse = callWithDetailedError("scene-search") { api.sceneSearch(authToken, searchRequest) }
        if (searchResponse.errorCode != null) {
            throw IllegalStateException("USGS scene-search hatası: ${searchResponse.errorCode} - ${searchResponse.errorMessage}")
        }
        val scenes = searchResponse.data?.results.orEmpty()
        val bestScene = scenes.firstOrNull()
            ?: throw IllegalStateException("USGS: bölge/tarih için uygun Landsat sahnesi bulunamadı")

        val optionsResponse = callWithDetailedError("download-options") {
            api.downloadOptions(authToken, DownloadOptionsRequest(datasetName = DATASET_NAME, entityIds = listOf(bestScene.entityId)))
        }
        if (optionsResponse.errorCode != null) {
            throw IllegalStateException("USGS download-options hatası: ${optionsResponse.errorCode}")
        }
        val thermalOption = optionsResponse.data.orEmpty().firstOrNull {
            it.available && it.productName.contains("Surface Temperature", ignoreCase = true)
        } ?: throw IllegalStateException("USGS: bu sahnede yüzey sıcaklığı ürünü bulunamadı/aktif değil")

        val requestResponse = callWithDetailedError("download-request") {
            api.downloadRequest(authToken, DownloadRequestBody(downloads = listOf(DownloadEntry(entityId = bestScene.entityId, productId = thermalOption.id))))
        }
        if (requestResponse.errorCode != null) {
            throw IllegalStateException("USGS download-request hatası: ${requestResponse.errorCode}")
        }
        val downloadUrl = requestResponse.data?.availableDownloads?.firstOrNull()?.url
            ?: throw IllegalStateException("USGS: indirme URL'i hazır değil (preparingDownloads durumunda olabilir, tekrar denenmeli)")

        // İndirme URL'i harici bir CDN/S3 linkine yönlendiriyor olabilir - doğrudan fetch edilir.
        val tiffBytes = downloadRawBytes(downloadUrl)
        val raster = GeoTiffDecoder.decodeSingleBand(tiffBytes, bbox, "LST")

        SourceScene(
            source = source,
            bands = mapOf("LST" to raster),
            acquiredEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun downloadRawBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("USGS dosya indirme hatası: ${response.code}")
            response.body?.bytes() ?: throw IllegalStateException("USGS: indirilen dosya boş")
        }
    }
}
