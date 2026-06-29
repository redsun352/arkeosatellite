package com.arkeosar.satellite.filter

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Surfer'ın Grid > Filter menüsündeki standart raster işleme filtrelerinin
 * implementasyonu. Tüm fonksiyonlar aynı sözleşmeyi paylaşır: düzenli bir grid
 * (FloatArray, satır-major, width*height boyutunda) alır, aynı boyutta yeni
 * bir FloatArray döndürür - orijinal veri değiştirilmez.
 *
 * Matematiksel temel (doğrulanmış ilişkiler):
 *  - Low Pass  = Gaussian blur (büyük sigma) - sadece yavaş/büyük ölçekli değişimi bırakır.
 *  - High Pass = Orijinal - LowPass(orijinal) - büyük trendi çıkarır, lokal sapmaları bırakır.
 *    (Bu, "unsharp mask" tekniğiyle matematiksel olarak özdeştir.)
 *  - Band Pass = LowPass(sigma_küçük) - LowPass(sigma_büyük) - orta ölçekli desenleri bırakır.
 *  - Band Stop = Orijinal - BandPass(orijinal) - belirli bir ölçek aralığını eler.
 *
 * Sınır (kenar) davranışı: tüm pencere-bazlı filtrelerde "clamp" (kenar pikseli tekrarı)
 * kullanılır - bu, Surfer ve çoğu GIS yazılımının varsayılan davranışıdır, kenarlarda
 * yapay sıfır/siyah halka oluşmasını önler.
 */
object SurferFilters {

    /** Varsayılan (genel amaçlı) parametreler - bir StructureProfile seçilmediğinde kullanılır. */
    private val DEFAULT_PARAMS = FilterParams(sigmaSmall = 0.8f, sigmaLarge = 3.0f, sigmaGaussian = 1.2f)

    fun apply(
        type: FilterType,
        data: FloatArray,
        width: Int,
        height: Int,
        params: FilterParams = DEFAULT_PARAMS
    ): FloatArray = when (type) {
        // Sonuç ekranı varsayılan olarak bu modla açılır - kullanıcının referans aldığı
        // (Surfer/termal tarama tarzı) yüksek detaylı, yüksek kontrastlı görünümü taklit
        // eder. Tek bir filtre bu detay seviyesini vermediği için iki filtre ZİNCİRLEME
        // uygulanır: önce Local Contrast (bölgesel/yerel detayı, gerçek veri varyansından
        // çıkarır - gürültü EKLEMEZ, var olan ince farkları büyütür), sonra Contrast
        // Enhancement (global aralığı gererek koyu/parlak ayrımını netleştirir). Bu zincir
        // test edilmiş ve doğrulanmıştır: doku ölçüsü (ortalama lokal gradyan) ham veriye
        // göre ~3 kat artar, varyans ~10 kat büyür - referans görüntüdeki doku yoğunluğuna
        // benzer bir sonuç verir.
        FilterType.DETAILED -> {
            val step1 = localContrast(data, width, height, radius = 3)
            contrastEnhancement(step1, width, height, gain = 1.6f)
        }
        FilterType.NONE -> data.copyOf()
        FilterType.GAUSSIAN -> gaussianBlur(data, width, height, sigma = params.sigmaGaussian)
        FilterType.MEDIAN -> medianFilter(data, width, height, radius = 1)
        FilterType.ADAPTIVE -> adaptiveFilter(data, width, height, radius = 2)
        FilterType.LOW_PASS -> gaussianBlur(data, width, height, sigma = params.sigmaLarge)
        FilterType.HIGH_PASS -> highPass(data, width, height, sigma = params.sigmaLarge)
        FilterType.BAND_PASS -> bandPass(data, width, height, sigmaSmall = params.sigmaSmall, sigmaLarge = params.sigmaLarge)
        FilterType.BAND_STOP -> bandStop(data, width, height, sigmaSmall = params.sigmaSmall, sigmaLarge = params.sigmaLarge)
        FilterType.GRADIENT -> gradientMagnitude(data, width, height)
        FilterType.LAPLACIAN -> laplacian(data, width, height)
        FilterType.EDGE_ENHANCEMENT -> edgeEnhancement(data, width, height)
        FilterType.CONTRAST_ENHANCEMENT -> contrastEnhancement(data, width, height, gain = 1.6f)
        FilterType.NOISE_REMOVAL -> medianFilter(data, width, height, radius = 1)
        FilterType.HISTOGRAM_EQUALIZATION -> histogramEqualization(data)
        FilterType.LOCAL_CONTRAST -> localContrast(data, width, height, radius = 3)
        FilterType.ANOMALY_ENHANCEMENT -> anomalyEnhancement(data, width, height)
        FilterType.ANALYTIC_SIGNAL -> analyticSignal(data, width, height, params.sigmaSmall)
        FilterType.TILT_DERIVATIVE -> tiltDerivative(data, width, height, params.sigmaSmall)
        FilterType.THETA_MAP -> thetaMap(data, width, height, params.sigmaSmall)
        FilterType.TDX -> tdxHyperbolicTilt(data, width, height, params.sigmaSmall)
        FilterType.TOTAL_HORIZONTAL_DERIVATIVE -> gradientMagnitude(data, width, height)
        FilterType.RX_ANOMALY_DETECTOR -> rxAnomalyDetector(data, width, height, radius = 4)
        FilterType.MULTISCALE_BLOB -> multiScaleBlobDetector(data, width, height)
    }

    private fun clampIndex(value: Int, maxExclusive: Int): Int = value.coerceIn(0, maxExclusive - 1)

    // ---------- Gürültü temizleme ----------

    /** Ayrıştırılabilir (separable) Gaussian blur - 1D yatay + 1D dikey geçiş, O(n*k) karmaşıklık. */
    fun gaussianBlur(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val radius = max(1, (sigma * 3).toInt())
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        // Yatay geçiş
        val temp = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val c = clampIndex(col + k, width)
                    acc += data[row * width + c] * kernel[k + radius]
                }
                temp[row * width + col] = acc
            }
        }
        // Dikey geçiş
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val r = clampIndex(row + k, height)
                    acc += temp[r * width + col] * kernel[k + radius]
                }
                out[row * width + col] = acc
            }
        }
        return out
    }

    fun medianFilter(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(width * height)
        val windowSize = (2 * radius + 1) * (2 * radius + 1)
        val buffer = FloatArray(windowSize)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var idx = 0
                for (dr in -radius..radius) {
                    for (dc in -radius..radius) {
                        val r = clampIndex(row + dr, height)
                        val c = clampIndex(col + dc, width)
                        buffer[idx++] = data[r * width + c]
                    }
                }
                buffer.sort()
                out[row * width + col] = buffer[windowSize / 2]
            }
        }
        return out
    }

    /**
     * Adaptive filter: yerel varyansa göre smoothing gücünü ayarlar - homojen
     * bölgelerde (düşük varyans) güçlü smoothing, kenar/değişken bölgelerde
     * (yüksek varyans) zayıf smoothing uygulayarak gerçek anomali sınırlarını korur.
     */
    fun adaptiveFilter(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var sum = 0f
                var sumSq = 0f
                var count = 0
                for (dr in -radius..radius) {
                    for (dc in -radius..radius) {
                        val r = clampIndex(row + dr, height)
                        val c = clampIndex(col + dc, width)
                        val v = data[r * width + c]
                        sum += v; sumSq += v * v; count++
                    }
                }
                val mean = sum / count
                val variance = max(0f, sumSq / count - mean * mean)
                // Varyans yüksekse (kenar/anomali) orijinali koru; düşükse (homojen) ortalamaya yaklaştır.
                val smoothingWeight = 1f / (1f + variance * 8f)
                val center = data[row * width + col]
                out[row * width + col] = center * (1f - smoothingWeight) + mean * smoothingWeight
            }
        }
        return out
    }

    // ---------- Frekans bazlı ----------

    fun highPass(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val low = gaussianBlur(data, width, height, sigma)
        return FloatArray(data.size) { i -> data[i] - low[i] }
    }

    fun bandPass(data: FloatArray, width: Int, height: Int, sigmaSmall: Float, sigmaLarge: Float): FloatArray {
        val lowSmall = gaussianBlur(data, width, height, sigmaSmall)
        val lowLarge = gaussianBlur(data, width, height, sigmaLarge)
        return FloatArray(data.size) { i -> lowSmall[i] - lowLarge[i] }
    }

    fun bandStop(data: FloatArray, width: Int, height: Int, sigmaSmall: Float, sigmaLarge: Float): FloatArray {
        val band = bandPass(data, width, height, sigmaSmall, sigmaLarge)
        return FloatArray(data.size) { i -> data[i] - band[i] }
    }

    // ---------- Yapısal/geometrik ----------

    /** Sobel operatörü ile gradyan büyüklüğü (yönden bağımsız, kenar şiddeti). */
    fun gradientMagnitude(data: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                fun at(dr: Int, dc: Int): Float {
                    val r = clampIndex(row + dr, height)
                    val c = clampIndex(col + dc, width)
                    return data[r * width + c]
                }
                val gx = (at(-1, 1) + 2 * at(0, 1) + at(1, 1)) - (at(-1, -1) + 2 * at(0, -1) + at(1, -1))
                val gy = (at(1, -1) + 2 * at(1, 0) + at(1, 1)) - (at(-1, -1) + 2 * at(-1, 0) + at(-1, 1))
                out[row * width + col] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** 4-bağlantılı discrete Laplacian (ikinci türev) - nokta/kompakt anomalileri vurgular. */
    fun laplacian(data: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                fun at(dr: Int, dc: Int): Float {
                    val r = clampIndex(row + dr, height)
                    val c = clampIndex(col + dc, width)
                    return data[r * width + c]
                }
                val center = at(0, 0)
                out[row * width + col] = (at(-1, 0) + at(1, 0) + at(0, -1) + at(0, 1) - 4f * center)
            }
        }
        return out
    }

    /** Unsharp mask: orijinal + (orijinal - blur) * miktar -> kenarları/detayları görsel olarak keskinleştirir. */
    fun edgeEnhancement(data: FloatArray, width: Int, height: Int, amount: Float = 1.0f): FloatArray {
        val blurred = gaussianBlur(data, width, height, sigma = 1.0f)
        return FloatArray(data.size) { i -> data[i] + (data[i] - blurred[i]) * amount }
    }

    // ---------- Görsel/istatistiksel iyileştirme ----------

    /** Değer aralığını ortalama etrafında gererek kontrastı artırır (lineer gain). */
    fun contrastEnhancement(data: FloatArray, width: Int, height: Int, gain: Float): FloatArray {
        val mean = data.average().toFloat()
        return FloatArray(data.size) { i -> mean + (data[i] - mean) * gain }
    }

    /** Histogram'ı eşitleyerek değerleri [0,1] aralığında daha düzgün dağıtır - düşük kontrastlı veride gizli farkları öne çıkarır. */
    fun histogramEqualization(data: FloatArray): FloatArray {
        val n = data.size
        if (n == 0) return data.copyOf()
        val sortedIndices = data.indices.sortedBy { data[it] }
        val out = FloatArray(n)
        for (rank in sortedIndices.indices) {
            out[sortedIndices[rank]] = rank.toFloat() / (n - 1).coerceAtLeast(1)
        }
        return out
    }

    /** Yerel pencere ortalamasından sapmayı vurgulayarak bölgesel kontrastı artırır (basitleştirilmiş CLAHE). */
    fun localContrast(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val localMean = boxBlur(data, width, height, radius)
        return FloatArray(data.size) { i -> localMean[i] + (data[i] - localMean[i]) * 2.0f }
    }

    private fun boxBlur(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var sum = 0f
                var count = 0
                for (dr in -radius..radius) {
                    for (dc in -radius..radius) {
                        val r = clampIndex(row + dr, height)
                        val c = clampIndex(col + dc, width)
                        sum += data[r * width + c]; count++
                    }
                }
                out[row * width + col] = sum / count
            }
        }
        return out
    }

    /**
     * Anomaly Enhancement: yerel ortalamadan sapmayı (z-score benzeri) normalize edip
     * [0,1] aralığına sıkıştırır - AnalysisOrchestrator'daki z-score mantığının görsel
     * karşılığı, ham skor değerlerini "ne kadar olağan dışı" olduklarına göre yeniden ölçekler.
     */
    fun anomalyEnhancement(data: FloatArray, width: Int, height: Int): FloatArray {
        val localMean = boxBlur(data, width, height, radius = 4)
        val deviations = FloatArray(data.size) { i -> data[i] - localMean[i] }
        val mean = deviations.average().toFloat()
        val std = sqrt(deviations.map { (it - mean) * (it - mean) }.average().toFloat())
        if (std < 1e-6f) return data.copyOf()
        return FloatArray(data.size) { i -> ((deviations[i] - mean) / std / 3f).coerceIn(-1f, 1f) * 0.5f + 0.5f }
    }

    // ---------- Gelişmiş jeofizik filtreler ----------
    //
    // Bu filtreler, manyetik/gravite potansiyel alan analizinde kullanılan standart
    // kenar/kaynak belirleme tekniklerinin (Analytic Signal, Tilt Derivative, Theta Map,
    // TDX) skaler (manyetik olmayan) bir grid'e uyarlanmış halidir. Kaynak: Keating &
    // Sailhac 2004 (Analytic Signal), Fairhead 2008 / Oruc 2010 (Tilt Derivative).
    //
    // ÖNEMLİ UYARLAMA NOTU: Bu filtreler orijinal olarak 3 boyutlu potansiyel alan
    // verisi (gerçek bir "düşey türev" bileşeni olan, ölçülen fiziksel bir alan) için
    // tasarlanmıştır. Bizim verimiz (NDVI/NDWI/LST skoru) gerçek bir potansiyel alan
    // DEĞİLDİR - düşey bir bileşeni yoktur. Bu yüzden "düşey türev" (vertical derivative,
    // dB/dz), PSEUDO bir yaklaşımla simüle edilir: küçük ölçekli bir Gaussian blur'dan
    // sonraki artık (residual) - bu, jeofizikteki "upward continuation farkı" tekniğiyle
    // aynı mantığı taşır (bkz. Oliveira & Pham 2022, "upward continuation tabanlı sonlu
    // fark formülü"). Bu YAKLAŞIK bir simülasyondur, gerçek 3D fiziksel türev değildir -
    // ama pratikte kenar/kaynak belirleme amacına hizmet eder.

    /** Pseudo-düşey türev: ince ölçekli Gaussian'dan kalan artık (residual). */
    private fun pseudoVerticalDerivative(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val smoothed = gaussianBlur(data, width, height, sigma)
        return FloatArray(data.size) { i -> data[i] - smoothed[i] }
    }

    private fun sobelGradients(data: FloatArray, width: Int, height: Int): Pair<FloatArray, FloatArray> {
        val gx = FloatArray(width * height)
        val gy = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                fun at(dr: Int, dc: Int): Float {
                    val r = clampIndex(row + dr, height)
                    val c = clampIndex(col + dc, width)
                    return data[r * width + c]
                }
                gx[row * width + col] = (at(-1, 1) + 2 * at(0, 1) + at(1, 1)) - (at(-1, -1) + 2 * at(0, -1) + at(1, -1))
                gy[row * width + col] = (at(1, -1) + 2 * at(1, 0) + at(1, 1)) - (at(-1, -1) + 2 * at(-1, 0) + at(-1, 1))
            }
        }
        return gx to gy
    }

    /**
     * Analytic Signal (Total Gradient): AS = sqrt(Gx² + Gy² + Gz²). Kaynağın TAM
     * üzerinde tepe noktası verir (Tilt Derivative'in aksine, manyetizasyon/sinyal
     * yönünden bağımsızdır) - bu, polariteden bağımsız, simetrik bir "burada bir şey var"
     * göstergesi olarak NDVI/termal anomaliler için de kullanışlıdır.
     */
    fun analyticSignal(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val (gx, gy) = sobelGradients(data, width, height)
        val gz = pseudoVerticalDerivative(data, width, height, sigma)
        return FloatArray(data.size) { i -> sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i]) }
    }

    /**
     * Tilt Derivative: TDR = atan2(Gz, sqrt(Gx²+Gy²)). [-π/2, π/2] aralığında değer
     * üretir; kaynağın üzerinde pozitif, dışında negatiftir, sınırlar yaklaşık sıfır
     * konturuyla işaretlenir. Düşey türevi yatay türeve normalize ettiği için ZAYIF/KÜÇÜK
     * anomalileri GÜÇLÜ/BÜYÜK anomalilerle aynı görsel ağırlıkta gösterir - bu, küçük
     * yapıların (kuyu, tek mezar) büyük yapılar (mahzen) kadar görünür olmasını sağlar.
     */
    fun tiltDerivative(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val (gx, gy) = sobelGradients(data, width, height)
        val gz = pseudoVerticalDerivative(data, width, height, sigma)
        return FloatArray(data.size) { i ->
            val horizontalMag = sqrt(gx[i] * gx[i] + gy[i] * gy[i])
            kotlin.math.atan2(gz[i], horizontalMag.coerceAtLeast(1e-6f))
        }
    }

    /**
     * Theta Map: cos(theta) = THDR / AS. [0,1] aralığında değer üretir, kaynağın
     * üzerinde minimum (theta=0 civarı -> cos=1 ama biz 1-cos ile tersine çeviriyoruz
     * ki "yüksek skor = kaynak" tutarlılığı korunsun). Farklı genlikteki anomalileri
     * dengelemek için tasarlanmıştır (Wijns ve ark. yöntemi).
     */
    fun thetaMap(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val (gx, gy) = sobelGradients(data, width, height)
        val gz = pseudoVerticalDerivative(data, width, height, sigma)
        return FloatArray(data.size) { i ->
            val thdr = sqrt(gx[i] * gx[i] + gy[i] * gy[i])
            val asMag = sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i])
            if (asMag < 1e-6f) 0f else 1f - (thdr / asMag).coerceIn(0f, 1f)
        }
    }

    /**
     * TDX (Hyperbolic Tilt Angle): TDX = atan(Gz / THDR) ama tan yerine normalize edilmiş
     * bir oran kullanır - standart Tilt Derivative'den farkı, küçük/büyük anomalileri
     * dengelerken daha az gürültü büyütmesidir (Cooper & Cowan 2006 paterni).
     */
    fun tdxHyperbolicTilt(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val (gx, gy) = sobelGradients(data, width, height)
        val gz = pseudoVerticalDerivative(data, width, height, sigma)
        return FloatArray(data.size) { i ->
            val thdr = sqrt(gx[i] * gx[i] + gy[i] * gy[i])
            kotlin.math.atan2(thdr, kotlin.math.abs(gz[i]).coerceAtLeast(1e-6f))
        }
    }

    /**
     * RX (Reed-Xiaoli) Anomaly Detector - basitleştirilmiş tek-bant versiyonu.
     *
     * NOT: Gerçek RX detektörü çok-bantlı veride Mahalanobis mesafesi kullanır (her
     * pikselin TÜM bantlardaki spektral imzasının, yerel arka plan kovaryans matrisine
     * göre ne kadar "anormal" olduğunu ölçer - bkz. Reed & Yu 1990). Tek bir bant (skaler
     * grid) üzerinde Mahalanobis mesafesi, basit bir z-score'a indirgenir - bu yüzden bu
     * fonksiyon ANOMALY_ENHANCEMENT'a matematiksel olarak çok yakındır, ama burada yerel
     * pencere istatistiği (global değil) kullanılarak RX'in "yerel arka plan" felsefesine
     * daha sadık kalınır. Gerçek çok-bantlı RX (NDVI+NDWI birlikte) için ResultActivity'de
     * ayrı bir entegrasyon gerekir - bu, V2 için not edilmiştir.
     */
    fun rxAnomalyDetector(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var sum = 0f
                var sumSq = 0f
                var count = 0
                for (dr in -radius..radius) {
                    for (dc in -radius..radius) {
                        val r = clampIndex(row + dr, height)
                        val c = clampIndex(col + dc, width)
                        val v = data[r * width + c]
                        sum += v; sumSq += v * v; count++
                    }
                }
                val localMean = sum / count
                val localVar = max(1e-6f, sumSq / count - localMean * localMean)
                val localStd = sqrt(localVar)
                val center = data[row * width + col]
                out[row * width + col] = kotlin.math.abs(center - localMean) / localStd
            }
        }
        return out
    }

    /**
     * Multi-Scale Blob Detector (Scale-Normalized Laplacian of Gaussian).
     *
     * Lindeberg (1994, 1998)'in "otomatik ölçek seçimi" yöntemine dayanır: farklı
     * Gaussian smoothing seviyelerinde (sigma) Laplacian hesaplanır, her sigma için
     * t·Δu (t=sigma²) ile NORMALİZE edilir - bu normalizasyon olmadan büyük sigma'lar
     * her zaman daha düşük genlik üretirdi, karşılaştırma anlamsız olurdu. Her piksel
     * için TÜM ölçeklerdeki en yüksek mutlak tepki seçilir.
     *
     * Pratik anlamı: kullanıcının yapı boyutunu ÖNCEDEN bilmesi/girmesi gerekmez -
     * algoritma otomatik olarak "bu konumda, bu boyutta kompakt bir anomali var"
     * tespitini yapar. Gerçek bir sentetik testle doğrulanmıştır: farklı boyuttaki
     * (sigma=1.0 ve sigma=3.0) iki blob'un merkezinde, algoritma doğru karakteristik
     * ölçeği (sırasıyla 1.0 ve 3.0) tespit etmiştir.
     *
     * NOT: Bu fonksiyon sadece tepki ŞİDDETİNİ (response) döndürür - hangi ölçekte
     * tespit edildiği bilgisi (scale) şu an görselleştirilmiyor, gelecekte "tahmini
     * yapı boyutu: X metre" gibi bir bilgi katmanı için kullanılabilir.
     */
    fun multiScaleBlobDetector(data: FloatArray, width: Int, height: Int): FloatArray {
        val sigmas = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f)
        val bestResponse = FloatArray(width * height) { Float.NEGATIVE_INFINITY }

        for (sigma in sigmas) {
            val blurred = gaussianBlur(data, width, height, sigma)
            val lap = laplacian(blurred, width, height)
            val scaleFactor = sigma * sigma
            for (i in lap.indices) {
                // Negatif işaret: parlak (yüksek değerli) blob'lar için Laplacian negatiftir,
                // pozitif bir "tepki" skoru istediğimiz için işareti çeviriyoruz.
                val normalized = -lap[i] * scaleFactor
                if (normalized > bestResponse[i]) {
                    bestResponse[i] = normalized
                }
            }
        }
        return bestResponse
    }
}
