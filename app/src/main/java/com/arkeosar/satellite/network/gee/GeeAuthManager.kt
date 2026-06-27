package com.arkeosar.satellite.network.gee

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * GEE REST API çağrıları için OAuth2 access token üretir.
 *
 * GÜVENLİK NOTU: service account JSON key dosyası APK içine (assets/) gömülüdür.
 * Bu, decompile edilebilir bir uygulamada hassas bir anahtarı dağıtmak demektir -
 * bilinçli olarak kabul edilmiş bir trade-off (kişisel/araştırma kullanımı için).
 * Üretim/yayın senaryosunda bunun yerine bir backend aracılığıyla token alınması
 * önerilir.
 */
class GeeAuthManager(private val serviceAccountJsonStream: () -> InputStream) {

    companion object {
        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/earthengine.readonly",
            "https://www.googleapis.com/auth/cloud-platform.read-only"
        )
    }

    @Volatile private var cachedCredentials: GoogleCredentials? = null

    /** Geçerli bir access token döner; gerekirse yeniler. */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val creds = cachedCredentials ?: loadCredentials().also { cachedCredentials = it }
        creds.refreshIfExpired()
        creds.accessToken?.tokenValue
            ?: throw IllegalStateException("GEE access token alınamadı")
    }

    private fun loadCredentials(): GoogleCredentials {
        serviceAccountJsonStream().use { stream ->
            return ServiceAccountCredentials.fromStream(stream)
                .createScoped(SCOPES)
        }
    }
}
