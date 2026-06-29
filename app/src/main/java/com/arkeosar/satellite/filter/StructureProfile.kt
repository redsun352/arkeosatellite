package com.arkeosar.satellite.filter

/**
 * Aranan yeraltı yapısının tipine göre önceden ayarlanmış filtre profili.
 *
 * ÖNEMLİ BİLİMSEL NOT: Buradaki boyut aralıkları genel arkeolojik/mimari literatürden
 * çıkarılmış TAHMİNİ başlangıç değerleridir - bölgeye, döneme ve kültüre göre büyük
 * farklılık gösterebilir. Kullanıcı elle ince ayar yapabilir (bkz. customSizeMeters).
 *
 * Bu profil sistemi filtrenin HANGİ ÖLÇEKTE arama yaptığını ayarlar - ama Sentinel-2
 * (10m/piksel) gibi kaynakların FİZİKSEL çözünürlük limitini ortadan kaldırmaz. 2-4m'lik
 * bir yapı hâlâ bir pikselin altında kalabilir; bu profil, var olan sinyali en iyi
 * şekilde ortaya çıkarmaya çalışır, yokluğunda bir şey icat etmez.
 *
 * @param typicalSizeMeters yapının tipik genişliği/çapı (metre) - filtre sigma'larını
 *   gerçek dünya ölçeğinden piksel ölçeğine çevirmek için kullanılır.
 * @param isElongated true ise yapı doğrusal/uzanan bir formdadır (tünel, koridor, giriş) -
 *   bu durumda Gradient/yönlü filtrelere daha fazla ağırlık verilir.
 * @param recommendedFilters bu yapı tipi için önerilen filtre sırası (kullanıcı tek tek
 *   deneyebilir, ilk eleman en isabetli olması beklenen filtredir).
 */
enum class StructureProfile(
    val label: String,
    val typicalSizeMeters: Double,
    val isElongated: Boolean,
    val recommendedFilters: List<FilterType>
) {
    WELL(
        label = "Kuyu",
        typicalSizeMeters = 1.5,
        isElongated = false,
        recommendedFilters = listOf(FilterType.LAYER_SHALLOW, FilterType.TILT_DERIVATIVE, FilterType.LAPLACIAN, FilterType.ANALYTIC_SIGNAL, FilterType.HIGH_PASS, FilterType.ANOMALY_ENHANCEMENT)
    ),
    GRAVE(
        label = "Mezar",
        typicalSizeMeters = 2.5,
        isElongated = false,
        recommendedFilters = listOf(FilterType.STRUCTURE_OUTLINE, FilterType.LAYER_SHALLOW, FilterType.TILT_DERIVATIVE, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT, FilterType.LOCAL_CONTRAST)
    ),
    SARCOPHAGUS(
        label = "Lahit",
        typicalSizeMeters = 2.2,
        isElongated = false,
        recommendedFilters = listOf(FilterType.STRUCTURE_OUTLINE, FilterType.LAYER_SHALLOW, FilterType.TILT_DERIVATIVE, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT, FilterType.GRADIENT)
    ),
    CHAMBER(
        label = "Oda",
        typicalSizeMeters = 4.5,
        isElongated = false,
        recommendedFilters = listOf(FilterType.STRUCTURE_OUTLINE, FilterType.LAYER_MEDIUM, FilterType.ANALYTIC_SIGNAL, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT, FilterType.LOCAL_CONTRAST)
    ),
    CRYPT(
        label = "Mahzen",
        typicalSizeMeters = 6.0,
        isElongated = false,
        recommendedFilters = listOf(FilterType.STRUCTURE_OUTLINE, FilterType.LAYER_DEEP, FilterType.ANALYTIC_SIGNAL, FilterType.BAND_PASS, FilterType.LOW_PASS, FilterType.LOCAL_CONTRAST)
    ),
    VOID(
        label = "Boşluk (Genel)",
        typicalSizeMeters = 3.0,
        isElongated = false,
        recommendedFilters = listOf(FilterType.RX_MULTIBAND_LOCAL, FilterType.GLCM_CONTRAST, FilterType.LAYER_MEDIUM, FilterType.MULTISCALE_BLOB, FilterType.RX_MULTIBAND_GLOBAL, FilterType.ANOMALY_ENHANCEMENT, FilterType.BAND_PASS)
    ),
    ENTRANCE(
        label = "Giriş",
        typicalSizeMeters = 1.5,
        isElongated = true,
        recommendedFilters = listOf(FilterType.LAYER_SHALLOW, FilterType.TDX, FilterType.GRADIENT, FilterType.HIGH_PASS, FilterType.EDGE_ENHANCEMENT)
    ),
    TUNNEL(
        label = "Tünel",
        typicalSizeMeters = 1.8,
        isElongated = true,
        recommendedFilters = listOf(FilterType.LAYER_MEDIUM, FilterType.TDX, FilterType.GRADIENT, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT)
    ),
    CORRIDOR(
        label = "Koridor",
        typicalSizeMeters = 2.0,
        isElongated = true,
        recommendedFilters = listOf(FilterType.LAYER_MEDIUM, FilterType.TDX, FilterType.GRADIENT, FilterType.BAND_PASS, FilterType.LOCAL_CONTRAST)
    );

    /**
     * typicalSizeMeters'i, verilen grid çözünürlüğüne (metre/piksel) göre piksel cinsinden
     * bir sigma değerine çevirir. Band Pass için "küçük" sigma yapı boyutunun ~%40'ı,
     * "büyük" sigma yapı boyutunun ~2 katı olacak şekilde ayarlanır - bu, yapı boyutuna
     * yakın desenleri bırakıp daha küçük gürültüyü ve daha büyük arazi trendini eler.
     */
    fun toFilterParams(metersPerPixel: Double, overrideSizeMeters: Double? = null): FilterParams {
        val sizeMeters = overrideSizeMeters ?: typicalSizeMeters
        val sizeInPixels = (sizeMeters / metersPerPixel).coerceAtLeast(0.5)
        return FilterParams(
            sigmaSmall = (sizeInPixels * 0.4).toFloat().coerceAtLeast(0.3f),
            sigmaLarge = (sizeInPixels * 2.0).toFloat().coerceAtLeast(1.0f),
            sigmaGaussian = (sizeInPixels * 0.3).toFloat().coerceAtLeast(0.5f)
        )
    }
}

data class FilterParams(
    val sigmaSmall: Float,
    val sigmaLarge: Float,
    val sigmaGaussian: Float
)
