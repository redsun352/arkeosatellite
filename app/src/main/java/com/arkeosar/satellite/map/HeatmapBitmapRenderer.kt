package com.arkeosar.satellite.map

import android.graphics.Bitmap
import android.graphics.Color
import com.arkeosar.satellite.model.HeightmapGrid

/**
 * HeightmapGrid'i (96x96 skor matrisi) kesintisiz, akıcı geçişli bir Bitmap'e çevirir -
 * bu bitmap GoogleMap'e bir GroundOverlay olarak yapıştırılır (TileOverlay/Circle yığını
 * değil), kullanıcının istediği "Surfer/termal tarama tarzı" sürekli doku için.
 *
 * İki renk paleti desteklenir:
 *  - SEPIA: koyu kahverengi/siyahtan krem/açık sarıya geçiş - kullanıcının referans
 *    aldığı görüntüdeki (eski termal kamera/GPR tarama çıktısı tarzı) görünüm.
 *  - CLASSIC: mevcut mavi-sarı-kırmızı bilimsel renk skalası (ResultActivity'nin
 *    eski Circle tabanlı gösteriminde kullanılan renklerle aynı).
 *
 * AKIŞKANLIK NOTU: Bitmap önce grid çözünürlüğünde (örn. 96x96) üretilir, sonra
 * Bitmap.createScaledBitmap ile daha büyük bir hedef boyuta (UPSCALE_TARGET) BİLİNEAR
 * FİLTRELEME kullanılarak büyütülür - bu, Android'in resmi dokümantasyonunda "bilinear
 * filtering kullanıldığında ölçeklendirme sırasında daha iyi görüntü kalitesi" sağladığı
 * doğrulanmış bir tekniktir. Sonuç, sert piksel kenarları yerine yumuşak/organik geçişler
 * veren bir doku üretir - kullanıcının referans aldığı görüntüdeki akışkan görünüme
 * yaklaşmak için eklenmiştir.
 */
object HeatmapBitmapRenderer {

    enum class Palette { SEPIA, CLASSIC }

    private const val UPSCALE_TARGET = 1024  // 512'den 1024'e çıkardık — daha yüksek son çözünürlük
    private const val BLUR_SIGMA = 1.2f      // Gaussian blur sigma — görsel yumuşatma için

    fun render(grid: HeightmapGrid, palette: Palette, alpha: Int = 200): Bitmap {
        // 1) Grid değerlerine önce Gaussian blur uygula — piksel piksel keskin geçişleri yumuşat.
        //    Bu, filtrelerin matematiksel sonuçlarını bozmaz; sadece görsel geçişleri pürüzsüzleştirir.
        val smoothed = gaussianBlurGrid(grid.scores, grid.width, grid.height, BLUR_SIGMA)

        val rawBitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
        for (row in 0 until grid.height) {
            for (col in 0 until grid.width) {
                val score = smoothed[row * grid.width + col].coerceIn(0f, 1f)
                val color = when (palette) {
                    Palette.SEPIA -> sepiaColor(score, alpha)
                    Palette.CLASSIC -> classicColor(score, alpha)
                }
                rawBitmap.setPixel(col, row, color)
            }
        }
        // 2) Bilinear filtreleme ile büyüt — pürüzsüz piksel arası geçişler için.
        val scaled = Bitmap.createScaledBitmap(rawBitmap, UPSCALE_TARGET, UPSCALE_TARGET, true)
        if (scaled !== rawBitmap) rawBitmap.recycle()
        return scaled
    }

    /**
     * Separable 1D Gaussian blur (yatay + dikey iki geçiş) — O(n*radius) karmaşıklığı,
     * 2D convolution'dan çok daha hızlı. Kernel yarıçapı sigma*3 ile sınırlandırılır.
     */
    private fun gaussianBlurGrid(data: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val radius = (sigma * 3).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1) { i ->
            val x = (i - radius).toFloat()
            kotlin.math.exp(-(x * x) / (2f * sigma * sigma))
        }
        val kernelSum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= kernelSum

        // Yatay geçiş
        val temp = FloatArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val c = (col + k).coerceIn(0, width - 1)
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
                    val r = (row + k).coerceIn(0, height - 1)
                    acc += temp[r * width + col] * kernel[k + radius]
                }
                out[row * width + col] = acc
            }
        }
        return out
    }

    /**
     * Sepya/termal skala: 0.0 -> koyu kahve-siyah (#0D0A05), 0.5 -> orta kahve-turuncu
     * (#7A4A1F), 1.0 -> parlak krem-sarı (#F5E6B8). Referans görüntüdeki "ısı izi"
     * hissini taklit eder - düşük sinyal koyu/soğuk, yüksek sinyal parlak/sıcak görünür.
     */
    private fun sepiaColor(score: Float, alpha: Int): Int {
        val (r, g, b) = when {
            score < 0.5f -> {
                val t = score / 0.5f
                Triple(
                    lerp(13, 122, t),
                    lerp(10, 74, t),
                    lerp(5, 31, t)
                )
            }
            else -> {
                val t = (score - 0.5f) / 0.5f
                Triple(
                    lerp(122, 245, t),
                    lerp(74, 230, t),
                    lerp(31, 184, t)
                )
            }
        }
        return Color.argb(alpha, r, g, b)
    }

    /** Mevcut mavi-sarı-kırmızı bilimsel skala (ResultActivity.scoreToColor ile tutarlı). */
    private fun classicColor(score: Float, alpha: Int): Int {
        val (r, g, b) = when {
            score < 0.5f -> {
                val t = score / 0.5f
                Triple(lerp(33, 255, t), lerp(102, 220, t), lerp(172, 60, t))
            }
            else -> {
                val t = (score - 0.5f) / 0.5f
                Triple(255, lerp(220, 30, t), lerp(60, 0, t))
            }
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt().coerceIn(0, 255)
}
