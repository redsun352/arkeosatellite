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
        recommendedFilters = listOf(FilterType.LAYER_SHALLOW, FilterType.TILT_DERIVATIVE, FilterType.MORPHOLOGICAL_OPENING, FilterType.LAPLACIAN, FilterType.ANALYTIC_SIGNAL, FilterType.HIGH_PASS, FilterType.ANOMALY_ENHANCEMENT)
    ),
    GRAVE(
        label = "Mezar",
        typicalSizeMeters = 2.5,
        isElongated = false,
        recommendedFilters = listOf(
            FilterType.RX_ANOMALY_DETECTOR,       // Reed-Xiaoli: doğrudan gömülü kalıntı tespitinde kullanılmış (literatür)
            FilterType.IRON_OXIDE_INDEX,           // Bozunan organik madde → demir oksit izleri
            FilterType.PCA_FUSION,                 // Crop mark / soil / vegetation bileşen ayrıştırma (literatür)
            FilterType.CONSENSUS_SCORE,            // Çoklu bağımsız filtre oylaması → gürültü azaltma
            FilterType.ANOMALY_ENHANCEMENT,        // Lokal z-score, ince sinyalleri güçlendirir
            FilterType.GETIS_ORD_GI_STAR,          // Hot spot: mezar gruplarında kümelenme tespiti
            FilterType.GLCM_CONTRAST,              // Doku analizi (literatürde gömülü anomali tespitinde kullanılmış)
            FilterType.MORPHOLOGICAL_OPENING,      // Küçük kompakt yapı → gürültü eleme, gerçek yapıyı koru
            FilterType.STRUCTURE_OUTLINE,          // Canny kenar tespiti, mezar sınırı
            FilterType.WAVELET_DETAIL,             // Yüksek frekanslı detaylar → ince sınır sinyalleri
            FilterType.LAYER_SHALLOW,              // Yüzeysel katman (mezar derinliği)
            FilterType.TILT_DERIVATIVE,            // Jeofizik filtre, kompakt anomalileri dengeler
            FilterType.RX_MULTIBAND_LOCAL,         // Yerel çok-bantlı anomali tespiti
            FilterType.BAND_PASS,
            FilterType.EDGE_ENHANCEMENT,
            FilterType.LOCAL_CONTRAST
        )
    ),
    SARCOPHAGUS(
        label = "Lahit",
        typicalSizeMeters = 2.2,
        isElongated = false,
        recommendedFilters = listOf(
            FilterType.STRUCTURE_OUTLINE,          // Rijit taş/mermer → net kenar çizgisi
            FilterType.MORPHOLOGICAL_GRADIENT,     // Kalın kenar (Lahit'in sert sınırı)
            FilterType.IRON_OXIDE_INDEX,           // Taş mineralojisi + çevre toprak değişimi
            FilterType.CLAY_MINERAL_RATIO,         // Taş çevresindeki kil mineral alterasyonu
            FilterType.RX_ANOMALY_DETECTOR,        // Spektral anomali tespiti
            FilterType.CONSENSUS_SCORE,            // Çoklu filtre oylaması
            FilterType.NEAREST_NEIGHBOR,           // Net hücresel sınırlar — lahit blok yapısı
            FilterType.LAYER_SHALLOW,
            FilterType.TILT_DERIVATIVE,
            FilterType.BAND_PASS,
            FilterType.EDGE_ENHANCEMENT,
            FilterType.GRADIENT
        )
    ),
    CHAMBER(
        label = "Oda",
        typicalSizeMeters = 4.5,
        isElongated = false,
        recommendedFilters = listOf(FilterType.STRUCTURE_OUTLINE, FilterType.TRIANGULATION_LINEAR, FilterType.NEAREST_NEIGHBOR, FilterType.MORPHOLOGICAL_GRADIENT, FilterType.LAYER_MEDIUM, FilterType.ANALYTIC_SIGNAL, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT, FilterType.LOCAL_CONTRAST)
    ),
    CRYPT(
        label = "Mahzen",
        typicalSizeMeters = 6.0,
        isElongated = false,
        recommendedFilters = listOf(FilterType.MINIMUM_CURVATURE, FilterType.KRIGING_RESIDUAL, FilterType.RBF_RESIDUAL, FilterType.STRUCTURE_OUTLINE, FilterType.LAYER_DEEP, FilterType.ANALYTIC_SIGNAL, FilterType.BAND_PASS, FilterType.LOW_PASS, FilterType.LOCAL_CONTRAST)
    ),
    VOID(
        label = "Boşluk (Genel)",
        typicalSizeMeters = 3.0,
        isElongated = false,
        recommendedFilters = listOf(FilterType.CONSENSUS_SCORE, FilterType.GETIS_ORD_GI_STAR, FilterType.LOCAL_MORANS_I, FilterType.COKRIGING, FilterType.DATA_METRICS_DENSITY, FilterType.NATURAL_NEIGHBOR, FilterType.MODIFIED_SHEPARD, FilterType.POLYNOMIAL_REGRESSION, FilterType.INVERSE_DISTANCE_POWER, FilterType.MOVING_AVERAGE, FilterType.WAVELET_DETAIL, FilterType.RX_MULTIBAND_LOCAL, FilterType.STANDARD_DEVIATION, FilterType.GLCM_CONTRAST, FilterType.LAYER_MEDIUM, FilterType.MULTISCALE_BLOB, FilterType.RX_MULTIBAND_GLOBAL, FilterType.ANOMALY_ENHANCEMENT, FilterType.BAND_PASS)
    ),
    ENTRANCE(
        label = "Giriş",
        typicalSizeMeters = 1.5,
        isElongated = true,
        recommendedFilters = listOf(FilterType.RIDGE_DETECTOR, FilterType.LAYER_SHALLOW, FilterType.TDX, FilterType.GRADIENT, FilterType.HIGH_PASS, FilterType.EDGE_ENHANCEMENT)
    ),
    TUNNEL(
        label = "Tünel",
        typicalSizeMeters = 1.8,
        isElongated = true,
        recommendedFilters = listOf(FilterType.RIDGE_DETECTOR, FilterType.COMPASS_GRADIENT, FilterType.LAYER_MEDIUM, FilterType.TDX, FilterType.GRADIENT, FilterType.BAND_PASS, FilterType.EDGE_ENHANCEMENT)
    ),
    CORRIDOR(
        label = "Koridor",
        typicalSizeMeters = 2.0,
        isElongated = true,
        recommendedFilters = listOf(FilterType.RIDGE_DETECTOR, FilterType.LAYER_MEDIUM, FilterType.TDX, FilterType.GRADIENT, FilterType.BAND_PASS, FilterType.LOCAL_CONTRAST)
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
