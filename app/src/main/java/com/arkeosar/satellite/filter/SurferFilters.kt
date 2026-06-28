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

    fun apply(type: FilterType, data: FloatArray, width: Int, height: Int): FloatArray = when (type) {
        FilterType.NONE -> data.copyOf()
        FilterType.GAUSSIAN -> gaussianBlur(data, width, height, sigma = 1.2f)
        FilterType.MEDIAN -> medianFilter(data, width, height, radius = 1)
        FilterType.ADAPTIVE -> adaptiveFilter(data, width, height, radius = 2)
        FilterType.LOW_PASS -> gaussianBlur(data, width, height, sigma = 2.5f)
        FilterType.HIGH_PASS -> highPass(data, width, height, sigma = 2.5f)
        FilterType.BAND_PASS -> bandPass(data, width, height, sigmaSmall = 0.8f, sigmaLarge = 3.0f)
        FilterType.BAND_STOP -> bandStop(data, width, height, sigmaSmall = 0.8f, sigmaLarge = 3.0f)
        FilterType.GRADIENT -> gradientMagnitude(data, width, height)
        FilterType.LAPLACIAN -> laplacian(data, width, height)
        FilterType.EDGE_ENHANCEMENT -> edgeEnhancement(data, width, height)
        FilterType.CONTRAST_ENHANCEMENT -> contrastEnhancement(data, width, height, gain = 1.6f)
        FilterType.NOISE_REMOVAL -> medianFilter(data, width, height, radius = 1)
        FilterType.HISTOGRAM_EQUALIZATION -> histogramEqualization(data)
        FilterType.LOCAL_CONTRAST -> localContrast(data, width, height, radius = 3)
        FilterType.ANOMALY_ENHANCEMENT -> anomalyEnhancement(data, width, height)
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
}
