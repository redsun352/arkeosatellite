import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties dosyasını oku - API key/secret'lar buradan gelir, kaynak koduna gömülmez.
// local.properties .gitignore içinde olmalı (Android Studio projelerinde varsayılan olarak öyledir).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
fun secret(key: String): String = localProps.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.arkeosar.satellite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arkeosar.satellite"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Google Maps API key, local.properties / manifest placeholder üzerinden enjekte edilir
        manifestPlaceholders["MAPS_API_KEY"] = secret("MAPS_API_KEY")

        // Uydu API kimlik bilgileri - local.properties üzerinden, koda gömülmez
        buildConfigField("String", "PLANET_API_KEY", "\"${secret("PLANET_API_KEY")}\"")
        buildConfigField("String", "COPERNICUS_CLIENT_ID", "\"${secret("COPERNICUS_CLIENT_ID")}\"")
        buildConfigField("String", "COPERNICUS_CLIENT_SECRET", "\"${secret("COPERNICUS_CLIENT_SECRET")}\"")
        buildConfigField("String", "USGS_USER", "\"${secret("USGS_USER")}\"")
        buildConfigField("String", "USGS_M2M_TOKEN", "\"${secret("USGS_M2M_TOKEN")}\"")
        buildConfigField("String", "EARTHDATA_TOKEN", "\"${secret("EARTHDATA_TOKEN")}\"")
        buildConfigField("String", "GEE_PROJECT_ID", "\"${secret("GEE_PROJECT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // google-auth-library gibi JVM-öncelikli kütüphaneler JAR'larının içine
    // INDEX.LIST, DEPENDENCIES, LICENSE gibi meta dosyaları koyar. Android'in
    // resource merge adımı, birden fazla bağımlılıkta aynı yol/isimde dosya
    // bulunca çakışma hatası verir (DuplicateRelativeFileException). Bu meta
    // dosyaları APK çalışma zamanında gerekli değildir, güvenle hariç tutulabilir.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Core Android / Kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Coroutines - asenkron ağ/GIS işlemleri için
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Maps + konum (polygon çizimi için)
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2") // PolyUtil, polygon yardımcıları

    // Ağ katmanı - Copernicus / USGS REST + GEE REST API çağrıları için
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Google Auth - GEE service account OAuth2 token üretimi için
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.2")

    // NOT: TensorFlow Lite gibi ML/filtre kütüphaneleri henüz eklenmedi - V1'de anomali
    // skoru basit istatistiksel formüllerle (AnalysisOrchestrator.computeScoreAt)
    // hesaplanıyor. İleri filtre modelleri gerektiğinde buraya eklenecek.

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
