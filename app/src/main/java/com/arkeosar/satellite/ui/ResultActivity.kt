package com.arkeosar.satellite.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.satellite.databinding.ActivityResultBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolygonOptions

/**
 * Analiz sonucunu harita üzerinde gösterir: taranan polygon sınırı + en yüksek
 * skorlu anomali hücrelerinin (en fazla 500 - bkz. MainActivity.runAnalysis)
 * basit renkli daireler olarak overlay'i.
 *
 * V1 sınırlaması: Gerçek piksel-bazlı ısı haritası (her hücre için ayrı renkli
 * kare/poligon) yerine, en yüksek skorlu hücrelerin nokta-bazlı gösterimi kullanılıyor -
 * tam çözünürlüklü heatmap (V2) ayrı bir iş olarak planlanıyor.
 */
class ResultActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_CELL_COUNT = "extra_cell_count"
        const val EXTRA_SOURCES = "extra_sources"
        const val EXTRA_FAILED_SOURCES = "extra_failed_sources"
        const val EXTRA_POLYGON_LATS = "extra_polygon_lats"
        const val EXTRA_POLYGON_LNGS = "extra_polygon_lngs"
        const val EXTRA_CELL_LATS = "extra_cell_lats"
        const val EXTRA_CELL_LNGS = "extra_cell_lngs"
        const val EXTRA_CELL_SCORES = "extra_cell_scores"
    }

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cellCount = intent.getIntExtra(EXTRA_CELL_COUNT, 0)
        val sourcesText = intent.getStringExtra(EXTRA_SOURCES) ?: "-"
        val failedSourcesText = intent.getStringExtra(EXTRA_FAILED_SOURCES) ?: ""

        binding.summaryText.text = buildString {
            append("Polygon içinde analiz edilen hücre sayısı: $cellCount\n")
            append("Kullanılan uydu kaynakları: $sourcesText")
            if (failedSourcesText.isNotBlank()) {
                append("\n\nBaşarısız olan kaynaklar:\n$failedSourcesText")
            }
        }

        val mapFragment = supportFragmentManager.findFragmentById(com.arkeosar.satellite.R.id.resultMapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        map.mapType = GoogleMap.MAP_TYPE_HYBRID

        val polygonLats = intent.getDoubleArrayExtra(EXTRA_POLYGON_LATS) ?: doubleArrayOf()
        val polygonLngs = intent.getDoubleArrayExtra(EXTRA_POLYGON_LNGS) ?: doubleArrayOf()
        val cellLats = intent.getDoubleArrayExtra(EXTRA_CELL_LATS) ?: doubleArrayOf()
        val cellLngs = intent.getDoubleArrayExtra(EXTRA_CELL_LNGS) ?: doubleArrayOf()
        val cellScores = intent.getDoubleArrayExtra(EXTRA_CELL_SCORES) ?: doubleArrayOf()

        if (polygonLats.isEmpty()) return

        val polygonPoints = polygonLats.indices.map { i -> LatLng(polygonLats[i], polygonLngs[i]) }

        map.addPolygon(
            PolygonOptions()
                .addAll(polygonPoints)
                .strokeColor(0xFF39D98A.toInt())
                .strokeWidth(3f)
                .fillColor(0x1A39D98A)
        )

        // Anomali hücrelerini skor bazlı renklendirilmiş daireler olarak çiz -
        // yüksek skor (anomali olasılığı yüksek) turuncu/kırmızıya yakın, düşük skor
        // şeffaf/soluk renkte gösterilir.
        for (i in cellLats.indices) {
            val score = cellScores.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
            val color = scoreToColor(score)
            map.addCircle(
                CircleOptions()
                    .center(LatLng(cellLats[i], cellLngs[i]))
                    .radius(15.0)
                    .fillColor(color)
                    .strokeWidth(0f)
            )
        }

        // Kamerayı polygon sınırlarına otomatik sığdır.
        val boundsBuilder = LatLngBounds.Builder()
        polygonPoints.forEach { boundsBuilder.include(it) }
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        } catch (e: IllegalStateException) {
            // Harita henüz layout'u tamamlamamış olabilir (boyutu 0) - sessizce geç,
            // kullanıcı haritayı zoom/pan ile kendi inceleyebilir.
        }
    }

    /** Skoru (0.0-1.0) düşükten yükseğe mavi -> sarı -> kırmızı geçişli bir ARGB renge çevirir. */
    private fun scoreToColor(score: Double): Int {
        val alpha = 160
        return when {
            score < 0.5 -> {
                val t = (score / 0.5).coerceIn(0.0, 1.0)
                val r = (33 + t * (255 - 33)).toInt()
                val g = (102 + t * (220 - 102)).toInt()
                val b = (172 + t * (60 - 172)).toInt()
                (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val t = ((score - 0.5) / 0.5).coerceIn(0.0, 1.0)
                val r = (255).toInt()
                val g = (220 - t * (220 - 30)).toInt()
                val b = (60 - t * 60).toInt()
                (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
