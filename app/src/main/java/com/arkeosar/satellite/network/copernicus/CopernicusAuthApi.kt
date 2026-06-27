package com.arkeosar.satellite.network.copernicus

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

data class CopernicusTokenResponse(
    val access_token: String,
    val expires_in: Int,
    val token_type: String
)

/**
 * Copernicus Data Space / Sentinel Hub OAuth2 token uç noktası.
 * client_credentials grant type - kullanıcı etkileşimi gerekmez (M2M).
 */
interface CopernicusAuthApi {
    @FormUrlEncoded
    @POST("auth/realms/CDSE/protocol/openid-connect/token")
    suspend fun fetchToken(
        @Field("grant_type") grantType: String = "client_credentials",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): CopernicusTokenResponse
}
