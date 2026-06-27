package com.arkeosar.satellite.network

/**
 * API anahtarları/secret'lar KAYNAK KODUNA gömülmez.
 * Bunun yerine BuildConfig alanları üzerinden enjekte edilir - bu alanlar
 * `local.properties` dosyasından (Git'e gitmeyen, .gitignore'da olan dosya)
 * build zamanında okunur. Bkz: app/build.gradle.kts -> buildConfigField satırları.
 *
 * NOT: GEE service-account JSON'u bu mekanizmanın dışında - o, bilinçli olarak
 * assets/ klasörüne gömülüyor (Hasan'ın kabul ettiği risk, bkz. GeeAuthManager).
 * Ama Planet/Copernicus/USGS/Earthdata anahtarları için bu daha temiz yöntemi
 * kullanıyoruz, çünkü onlar zaten environment variable şeklinde ayrı tutuluyordu.
 */
object SecureConfig {

    object Planet {
        val apiKey: String get() = com.arkeosar.satellite.BuildConfig.PLANET_API_KEY
        const val itemTypes = "PSScene"
        const val cloudMax = 0.25
    }

    object Copernicus {
        val clientId: String get() = com.arkeosar.satellite.BuildConfig.COPERNICUS_CLIENT_ID
        val clientSecret: String get() = com.arkeosar.satellite.BuildConfig.COPERNICUS_CLIENT_SECRET
        const val cloudMax = 30
        const val tokenUrl = "https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token"
        const val processApiUrl = "https://sh.dataspace.copernicus.eu/api/v1/process"
    }

    object Usgs {
        val user: String get() = com.arkeosar.satellite.BuildConfig.USGS_USER
        val m2mToken: String get() = com.arkeosar.satellite.BuildConfig.USGS_M2M_TOKEN
        const val baseUrl = "https://m2m.cr.usgs.gov/api/api/json/stable/"
    }

    object Earthdata {
        val token: String get() = com.arkeosar.satellite.BuildConfig.EARTHDATA_TOKEN
    }

    object Gee {
        const val projectId = "" // local.properties: GEE_PROJECT_ID -> manifest/BuildConfig'e taşınacak
        const val serviceAccountAssetPath = "gee_service_account.json" // assets/ altında
    }

    fun isConfigured(value: String): Boolean = value.isNotBlank()
}
