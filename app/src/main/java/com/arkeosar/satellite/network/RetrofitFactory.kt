package com.arkeosar.satellite.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Tüm uydu kaynakları için paylaşılan OkHttp/Retrofit istemci üretimi. */
object RetrofitFactory {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC // BODY açılırsa token/secret loglara düşebilir, dikkat
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // raster indirme uzun sürebilir
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    fun create(baseUrl: String, client: OkHttpClient = baseClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    fun clientWithInterceptor(interceptor: okhttp3.Interceptor): OkHttpClient =
        baseClient.newBuilder().addInterceptor(interceptor).build()
}
