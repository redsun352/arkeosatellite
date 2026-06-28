package com.arkeosar.satellite.ui

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.satellite.databinding.ActivityResultBinding
import com.arkeosar.satellite.gl.HeightmapGlRenderer
import com.arkeosar.satellite.model.HeightmapGrid
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolygonOptions

/**
 * Analiz sonucunu iki şekilde gösterir, kullanıcı bir toggle butonuyla geçiş yapar:
 *  1) Harita görünümü: taranan polygon sınırı + en yüksek skorlu hücrelerin (en fazla 500)
 *     renkli daireler olarak overlay'i (2D, coğrafi referanslı).
 *  2) 3D yüzey görünümü: HeightmapGrid'den (48x48 downsample edilmiş düzenli grid)
 *     OpenGL ile render edilen, skor=yükseklik mantığıyla çalışan bir yüzey -
 *     kullanıcının istediği "Surfer tarzı" 3D anomali görselleştirmesi.
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
        const val EXTRA_HEIGHTMAP_WIDTH = "extra_heightmap_width"
        const val EXTRA_HEIGHTMAP_HEIGHT = "extra_heightmap_height"
        const val EXTRA_HEIGHTMAP_SCORES = "extra_heightmap_scores"
    }

    private lateinit var binding: ActivityResultBinding
    private var glSurfaceView: GLSurfaceView? = null
    private var heightmapRenderer: HeightmapGlRenderer? = null
    private var showing3d = false

    // Dokunarak döndürme için son dokunuş pozisyonu
    private var lastTouchX = 0f

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

        setupHeightmapView()

        binding.btnToggleView.setOnClickListener { toggleView() }
    }

    private fun toggleView() {
        showing3d = !showing3d
        binding.heightmapContainer.visibility = if (showing3d) android.view.View.VISIBLE else android.view.View.GONE
        // Harita Fragment'ı XML'de tanımlı olduğu için view'ını doğrudan gizliyoruz/gösteriyoruz.
        mapFragmentView()?.visibility = if (showing3d) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnToggleView.setText(
            if (showing3d) com.arkeosar.satellite.R.string.btn_view_map else com.arkeosar.satellite.R.string.btn_view_3d
        )
    }

    private fun mapFragmentView(): android.view.View? =
        supportFragmentManager.findFragmentById(com.arkeosar.satellite.R.id.resultMapFragment)?.view

    /** HeightmapGrid verisini Intent'ten okuyup GLSurfaceView'i kurar (henüz görünür değil). */
    private fun setupHeightmapView() {
        val width = intent.getIntExtra(EXTRA_HEIGHTMAP_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHTMAP_HEIGHT, 0)
        val scores = intent.getFloatArrayExtra(EXTRA_HEIGHTMAP_SCORES)

        if (width <= 0 || height <= 0 || scores == null || scores.size != width * height) {
            // Heightmap verisi yoksa (örn. eski bir Intent ya da hata durumu) 3D butonu devre dışı.
            binding.btnToggleView.isEnabled = false
            return
        }

        val grid = HeightmapGrid(width = width, height = height, scores = scores)
        val renderer = HeightmapGlRenderer(grid)
        heightmapRenderer = renderer

        val surfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> lastTouchX = event.x
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - lastTouchX
                        renderer.rotationDegrees += dx * 0.5f
                        lastTouchX = event.x
                        requestRender()
                    }
                }
                return true
            }
        }
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        glSurfaceView = surfaceView
        binding.heightmapContainer.addView(
            surfaceView,
            0, // hintText'in altında kalmasın diye index 0'a (en alta) ekleniyor
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
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

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }
}
