package com.arkeosar.satellite.ui

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.satellite.databinding.ActivityResultBinding
import com.arkeosar.satellite.filter.FilterType
import com.arkeosar.satellite.filter.SurferFilters
import com.arkeosar.satellite.gl.HeightmapGlRenderer
import com.arkeosar.satellite.model.BoundingBox
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
 *  1) Harita görünümü: taranan polygon sınırı + HeightmapGrid'den türetilmiş, skor
 *     bazlı renklendirilmiş daireler (2D, coğrafi referanslı).
 *  2) 3D yüzey görünümü: HeightmapGrid'den OpenGL ile render edilen, skor=yükseklik
 *     mantığıyla çalışan bir yüzey ("Surfer tarzı" 3D anomali görselleştirmesi).
 *
 * Kullanıcı bir FİLTRE (Gaussian, Median, High Pass, Laplacian, vb. - bkz. SurferFilters)
 * seçtiğinde, bu filtre HeightmapGrid'in skor matrisine uygulanır ve HEM 3D yüzey HEM
 * 2D harita overlay'i güncellenir - ikisi de aynı (filtrelenmiş) veriden besleniyor.
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
        const val EXTRA_BBOX_MIN_LAT = "extra_bbox_min_lat"
        const val EXTRA_BBOX_MAX_LAT = "extra_bbox_max_lat"
        const val EXTRA_BBOX_MIN_LNG = "extra_bbox_min_lng"
        const val EXTRA_BBOX_MAX_LNG = "extra_bbox_max_lng"
    }

    private lateinit var binding: ActivityResultBinding
    private var glSurfaceView: GLSurfaceView? = null
    private var heightmapRenderer: HeightmapGlRenderer? = null
    private var googleMap: GoogleMap? = null
    private var showing3d = false

    private var lastTouchX = 0f

    private var originalGrid: HeightmapGrid? = null
    private var bbox: BoundingBox? = null
    private var currentFilter: FilterType = FilterType.NONE

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

        loadHeightmapAndBounds()
        setupFilterSpinner()

        val mapFragment = supportFragmentManager.findFragmentById(com.arkeosar.satellite.R.id.resultMapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        setupHeightmapView()

        binding.btnToggleView.setOnClickListener { toggleView() }
    }

    /** Intent'ten HeightmapGrid ve bbox verisini okur (filtre yeniden hesaplamaları için saklanır). */
    private fun loadHeightmapAndBounds() {
        val width = intent.getIntExtra(EXTRA_HEIGHTMAP_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHTMAP_HEIGHT, 0)
        val scores = intent.getFloatArrayExtra(EXTRA_HEIGHTMAP_SCORES)

        if (width > 0 && height > 0 && scores != null && scores.size == width * height) {
            originalGrid = HeightmapGrid(width = width, height = height, scores = scores)
        }

        val minLat = intent.getDoubleExtra(EXTRA_BBOX_MIN_LAT, Double.NaN)
        val maxLat = intent.getDoubleExtra(EXTRA_BBOX_MAX_LAT, Double.NaN)
        val minLng = intent.getDoubleExtra(EXTRA_BBOX_MIN_LNG, Double.NaN)
        val maxLng = intent.getDoubleExtra(EXTRA_BBOX_MAX_LNG, Double.NaN)
        if (!minLat.isNaN() && !maxLat.isNaN() && !minLng.isNaN() && !maxLng.isNaN()) {
            bbox = BoundingBox(minLat = minLat, maxLat = maxLat, minLng = minLng, maxLng = maxLng)
        }
    }

    private fun setupFilterSpinner() {
        val labels = FilterType.values().map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.filterSpinner.adapter = adapter

        binding.filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentFilter = FilterType.values()[position]
                applyFilterAndRefresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Seçilen filtreyi orijinal grid'e uygular, 3D yüzeyi ve 2D harita overlay'ini günceller. */
    private fun applyFilterAndRefresh() {
        val grid = originalGrid ?: return
        val filteredScores = SurferFilters.apply(currentFilter, grid.scores, grid.width, grid.height)

        // 3D yüzeyi güncelle - filtrelenmiş skorlar [0,1] aralığını aşabilir (örn. Laplacian,
        // High Pass negatif değerler üretebilir) - renderer'a vermeden önce normalize ediyoruz.
        val normalized = normalizeToUnitRange(filteredScores)
        val filteredGrid = HeightmapGrid(width = grid.width, height = grid.height, scores = normalized)
        heightmapRenderer?.updateGrid(filteredGrid)
        glSurfaceView?.requestRender()

        // 2D haritayı güncelle - mevcut overlay'leri temizleyip filtrelenmiş grid'den yeniden çiz.
        redrawMapOverlay(normalized)
    }

    /**
     * Filtre çıktısı (özellikle High Pass, Laplacian, Gradient gibi türev-bazlı filtreler)
     * [0,1] dışına çıkabilir ya da negatif olabilir - görselleştirme için [0,1]'e normalize edilir.
     * Bu, filtrenin GÖRELİ etkisini (en düşük->en yüksek) korur, mutlak skor anlamını değiştirir.
     */
    private fun normalizeToUnitRange(data: FloatArray): FloatArray {
        if (data.isEmpty()) return data
        val min = data.min()
        val max = data.max()
        val range = max - min
        if (range < 1e-6f) return FloatArray(data.size) { 0.5f }
        return FloatArray(data.size) { i -> (data[i] - min) / range }
    }

    private fun redrawMapOverlay(scores: FloatArray) {
        val map = googleMap ?: return
        val box = bbox ?: return
        val grid = originalGrid ?: return

        map.clear()
        drawPolygonOnMap(map)

        // Grid hücrelerini coğrafi koordinata çevirip haritaya çiz (heightmap grid'i 48x48,
        // bu boyutta nokta sayısı performans açısından sorunsuz).
        for (row in 0 until grid.height) {
            for (col in 0 until grid.width) {
                val score = scores[row * grid.width + col]
                if (score < 0.05f) continue // çok düşük skorları görsel gürültü olarak atla
                val lat = box.maxLat - (row.toDouble() / grid.height) * (box.maxLat - box.minLat)
                val lng = box.minLng + (col.toDouble() / grid.width) * (box.maxLng - box.minLng)
                map.addCircle(
                    CircleOptions()
                        .center(LatLng(lat, lng))
                        .radius(20.0)
                        .fillColor(scoreToColor(score.toDouble()))
                        .strokeWidth(0f)
                )
            }
        }
    }

    private fun drawPolygonOnMap(map: GoogleMap) {
        val polygonLats = intent.getDoubleArrayExtra(EXTRA_POLYGON_LATS) ?: doubleArrayOf()
        val polygonLngs = intent.getDoubleArrayExtra(EXTRA_POLYGON_LNGS) ?: doubleArrayOf()
        if (polygonLats.isEmpty()) return
        val polygonPoints = polygonLats.indices.map { i -> LatLng(polygonLats[i], polygonLngs[i]) }
        map.addPolygon(
            PolygonOptions()
                .addAll(polygonPoints)
                .strokeColor(0xFF39D98A.toInt())
                .strokeWidth(3f)
                .fillColor(0x1A39D98A)
        )
    }

    private fun toggleView() {
        showing3d = !showing3d
        binding.heightmapContainer.visibility = if (showing3d) android.view.View.VISIBLE else android.view.View.GONE
        mapFragmentView()?.visibility = if (showing3d) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnToggleView.setText(
            if (showing3d) com.arkeosar.satellite.R.string.btn_view_map else com.arkeosar.satellite.R.string.btn_view_3d
        )
    }

    private fun mapFragmentView(): android.view.View? =
        supportFragmentManager.findFragmentById(com.arkeosar.satellite.R.id.resultMapFragment)?.view

    /** GLSurfaceView'i kurar (henüz görünür değil) - orijinal (filtresiz) grid ile başlar. */
    private fun setupHeightmapView() {
        val grid = originalGrid
        if (grid == null) {
            binding.btnToggleView.isEnabled = false
            return
        }

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
            0,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onMapReady(map: GoogleMap) {
        map.mapType = GoogleMap.MAP_TYPE_HYBRID
        googleMap = map

        drawPolygonOnMap(map)

        // Başlangıçta (filtre seçilmeden) orijinal grid'i normalize edip çiz.
        originalGrid?.let { grid ->
            redrawMapOverlay(normalizeToUnitRange(grid.scores))
        }

        val polygonLats = intent.getDoubleArrayExtra(EXTRA_POLYGON_LATS) ?: doubleArrayOf()
        val polygonLngs = intent.getDoubleArrayExtra(EXTRA_POLYGON_LNGS) ?: doubleArrayOf()
        if (polygonLats.isEmpty()) return
        val polygonPoints = polygonLats.indices.map { i -> LatLng(polygonLats[i], polygonLngs[i]) }

        val boundsBuilder = LatLngBounds.Builder()
        polygonPoints.forEach { boundsBuilder.include(it) }
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        } catch (e: IllegalStateException) {
            // Harita henüz layout'u tamamlamamış olabilir (boyutu 0) - sessizce geç.
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
