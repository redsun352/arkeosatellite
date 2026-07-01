package com.arkeosar.satellite.ui

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.satellite.databinding.ActivityResultBinding
import com.arkeosar.satellite.filter.FilterParams
import com.arkeosar.satellite.filter.FilterType
import com.arkeosar.satellite.filter.StructureProfile
import com.arkeosar.satellite.filter.SurferFilters
import com.arkeosar.satellite.gl.HeightmapGlRenderer
import com.arkeosar.satellite.map.HeatmapBitmapRenderer
import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.model.HeightmapGrid
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
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
    private var currentFilter: FilterType = FilterType.DETAILED
    private var currentProfile: StructureProfile = StructureProfile.VOID
    private var customSizeMeters: Double? = null // kullanıcı elle boyut girerse profilin varsayılanını ezer
    private var currentOpacityPercent: Int = 85 // 0=tamamen şeffaf, 100=tamamen opak (SeekBar varsayılanıyla aynı)

    private var cellCount = 0
    private var sourcesText = "-"
    private var failedSourcesText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cellCount = intent.getIntExtra(EXTRA_CELL_COUNT, 0)
        sourcesText = intent.getStringExtra(EXTRA_SOURCES) ?: "-"
        failedSourcesText = intent.getStringExtra(EXTRA_FAILED_SOURCES) ?: ""

        renderSummaryText(filterWarning = null)

        loadHeightmapAndBounds()
        setupStructureSpinner()
        setupFilterSpinner()
        setupOpacitySeekBar()

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

    private var structureSpinnerInitialized = false

    private fun setupStructureSpinner() {
        val labels = StructureProfile.values().map { "${it.label} (~${it.typicalSizeMeters}m)" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.structureSpinner.adapter = adapter

        binding.structureSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentProfile = StructureProfile.values()[position]
                binding.customSizeInput.setText(currentProfile.typicalSizeMeters.toString())
                customSizeMeters = null // profil değişince elle girilen özel boyutu sıfırla

                // Android, bir Spinner'a adapter/listener atandığında OnItemSelectedListener'ı
                // OTOMATİK olarak bir kere tetikler (position=0 ile) - bu, kullanıcının
                // gerçek bir seçim yapması DEĞİLDİR. Bu otomatik ilk tetiklenmede filtreyi
                // değiştirMEyiz, çünkü o zaman varsayılan DETAILED görünüm yerine ilk yapı
                // profilinin filtresi uygulanırdı - kullanıcı ekranı ilk açtığında istediğimiz
                // "Detaylı Görünüm" varsayılanı bozulurdu.
                if (structureSpinnerInitialized) {
                    val recommendedFilter = currentProfile.recommendedFilters.firstOrNull() ?: FilterType.NONE
                    val filterPosition = FilterType.values().indexOf(recommendedFilter)
                    if (filterPosition >= 0) binding.filterSpinner.setSelection(filterPosition)
                }
                structureSpinnerInitialized = true
                applyFilterAndRefresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.customSizeInput.setText(currentProfile.typicalSizeMeters.toString())
        binding.customSizeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val entered = binding.customSizeInput.text.toString().toDoubleOrNull()
                if (entered != null && entered > 0) {
                    customSizeMeters = entered
                    applyFilterAndRefresh()
                }
            }
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

    /**
     * Sepya heatmap overlay'inin opaklığını kontrol eden slider. GoogleMap'in GroundOverlay
     * API'si "transparency" (şeffaflık) parametresi alır - bu, opaklığın TERSİDİR
     * (transparency=0 -> tam opak, transparency=1 -> tam şeffaf). Kullanıcı arayüzünde
     * "Opaklık" göstermek daha sezgisel olduğu için, dönüşümü burada yapıyoruz:
     * opacityPercent=100 (tam opak) -> transparency=0.0, opacityPercent=0 -> transparency=1.0.
     *
     * setTransparency() doğrudan mevcut GroundOverlay nesnesinde çağrılır - haritayı
     * (polygon + bitmap) yeniden çizmeye gerek YOKTUR, bu yüzden slider sürüklenirken
     * akıcı/anlık bir tepki sağlanır.
     */
    private fun setupOpacitySeekBar() {
        binding.opacitySeekBar.progress = currentOpacityPercent
        binding.opacityValueText.text = "$currentOpacityPercent%"

        binding.opacitySeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentOpacityPercent = progress
                binding.opacityValueText.text = "$progress%"
                val transparency = 1f - (progress / 100f)
                currentGroundOverlay?.transparency = transparency
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    /**
     * Grid'in (48x48 downsample edilmiş) bir piksellik karesinin gerçek dünyadaki metre
     * karşılığını hesaplar - polygon'un bbox'ı (gerçek coğrafi boyut) ile grid çözünürlüğünden
     * (48) türetilir. Bu değer, StructureProfile'daki "tipik boyut (metre)"yi doğru bir
     * piksel-cinsi sigma'ya çevirebilmek için gereklidir.
     */
    private fun computeMetersPerPixel(): Double {
        val box = bbox ?: return 10.0 // bbox yoksa Sentinel-2'nin native çözünürlüğüne (10m) geri dön
        val grid = originalGrid ?: return 10.0
        val avgLat = (box.minLat + box.maxLat) / 2.0
        val widthMeters = (box.maxLng - box.minLng) * 111_320.0 * Math.cos(Math.toRadians(avgLat))
        val heightMeters = (box.maxLat - box.minLat) * 111_320.0
        val metersPerPixelX = widthMeters / grid.width
        val metersPerPixelY = heightMeters / grid.height
        return (metersPerPixelX + metersPerPixelY) / 2.0
    }

    /**
     * Sonuç özetini (hücre sayısı, kaynaklar, başarısız kaynaklar) ve isteğe bağlı bir
     * FİLTREYE ÖZGÜ uyarı mesajını render eder. Bu fonksiyon HER ÇAĞRILDIĞINDA metni
     * SIFIRDAN oluşturur (önceki metne EKLEME yapmaz) - bu, kullanıcı filtreler arasında
     * geçiş yaptığında eski/alakasız uyarı mesajlarının (örn. "PCA için NDVI gerekir")
     * ekranda BİRİKİP kalmasını önler. Önceki bir bug'da, binding.summaryText.append()
     * her filtre denemesinde yeni bir satır eklediği için, kullanıcı önce PCA deneyip
     * sonra başka bir filtreye geçtiğinde PCA'nın hata mesajı ekranda KALICI olarak
     * görünmeye devam ediyordu - bu fonksiyon bu sorunu kökten çözer.
     */
    private fun renderSummaryText(filterWarning: String?) {
        binding.summaryText.text = buildString {
            append("Polygon içinde analiz edilen hücre sayısı: $cellCount\n")
            append("Kullanılan uydu kaynakları: $sourcesText")
            if (failedSourcesText.isNotBlank()) {
                append("\n\nBaşarısız olan kaynaklar:\n$failedSourcesText")
            }
            if (!filterWarning.isNullOrBlank()) {
                append("\n\n")
                append(filterWarning)
            }
        }
    }

    /** Seçilen filtreyi (yapı profiline göre kalibre edilmiş parametrelerle) orijinal grid'e uygular. */
    private fun applyFilterAndRefresh() {
        val grid = originalGrid ?: return
        val metersPerPixel = computeMetersPerPixel()
        val params: FilterParams = currentProfile.toFilterParams(metersPerPixel, customSizeMeters)

        // Çok bantlı filtreler (PCA, RX) İKİ BANT (NDVI+NDWI) gerektirir - normal apply()
        // akışından farklı bir çağrı yapısı kullanır. NDVI/NDWI mevcut değilse (örn. sadece
        // USGS/Planet kullanılmışsa) sessizce ham skora geri döner (kullanıcıya bilgi vermek
        // için status mesajı da gösterilir, ama HER ZAMAN renderSummaryText ile SIFIRDAN
        // yazılır - bir önceki filtre denemesinden kalma mesaj asla birikmez).
        val multiBandFilters = setOf(FilterType.PCA_FUSION, FilterType.RX_MULTIBAND_GLOBAL, FilterType.RX_MULTIBAND_LOCAL, FilterType.COKRIGING)
        val mineralFilters = setOf(FilterType.IRON_OXIDE_INDEX, FilterType.CLAY_MINERAL_RATIO)
        val filteredScores = when {
            currentFilter in multiBandFilters -> {
                val ndvi = grid.rawNdvi
                val ndwi = grid.rawNdwi
                if (ndvi != null && ndwi != null) {
                    renderSummaryText(filterWarning = null)
                    when (currentFilter) {
                        FilterType.PCA_FUSION -> SurferFilters.pcaAnomalyFusion(ndvi, ndwi)
                        FilterType.RX_MULTIBAND_GLOBAL -> SurferFilters.rxMultiBandGlobal(ndvi, ndwi)
                        FilterType.RX_MULTIBAND_LOCAL -> SurferFilters.rxMultiBandLocal(ndvi, ndwi, grid.width, grid.height, radius = 4)
                        FilterType.COKRIGING -> SurferFilters.cokrigingPredict(ndvi, ndwi, grid.width, grid.height)
                        else -> grid.scores.copyOf()
                    }
                } else {
                    renderSummaryText(filterWarning = getString(com.arkeosar.satellite.R.string.error_pca_requires_sentinel))
                    grid.scores.copyOf()
                }
            }
            currentFilter in mineralFilters -> {
                val ioi = grid.rawIoi
                val cmr = grid.rawCmr
                if (ioi != null && cmr != null) {
                    renderSummaryText(filterWarning = null)
                    when (currentFilter) {
                        FilterType.IRON_OXIDE_INDEX -> ioi.copyOf()
                        FilterType.CLAY_MINERAL_RATIO -> cmr.copyOf()
                        else -> grid.scores.copyOf()
                    }
                } else {
                    renderSummaryText(filterWarning = "Mineral analizi için Sentinel-2 (B02+B11+B12 içeren) veri gereklidir. Analizi yeniden çalıştırın.")
                    grid.scores.copyOf()
                }
            }
            else -> {
                renderSummaryText(filterWarning = null)
                SurferFilters.apply(currentFilter, grid.scores, grid.width, grid.height, params)
            }
        }

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

    private var currentGroundOverlay: GroundOverlay? = null

    private fun redrawMapOverlay(scores: FloatArray) {
        val map = googleMap ?: return
        val box = bbox ?: return
        val grid = originalGrid ?: return

        map.clear()
        currentGroundOverlay = null
        drawPolygonOnMap(map)

        // Kesintisiz, piksel-piksel boyalı bir ısı haritası: yüzlerce ayrı Circle nesnesi
        // eklemek yerine (önceki yaklaşım - "yamalı" görünüme ve performans sorununa yol
        // açıyordu), grid'i tek bir Bitmap'e render edip GroundOverlay olarak haritaya
        // sabitliyoruz. Bu, kullanıcının referans aldığı "sürekli doku" görünümünü verir.
        val scoredGrid = HeightmapGrid(width = grid.width, height = grid.height, scores = scores)
        val bitmap = HeatmapBitmapRenderer.render(scoredGrid, HeatmapBitmapRenderer.Palette.SEPIA)

        val bounds = LatLngBounds.Builder()
            .include(LatLng(box.minLat, box.minLng))
            .include(LatLng(box.maxLat, box.maxLng))
            .build()
        currentGroundOverlay = map.addGroundOverlay(
            GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .positionFromBounds(bounds)
                .transparency(1f - (currentOpacityPercent / 100f)) // kullanıcının opaklık slider'ından
        )
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

        // Varsayılan filtre (DETAILED) burada uygulanır - ham/filtresiz veri DEĞİL.
        // applyFilterAndRefresh, currentFilter'a göre (başlangıçta DETAILED) hem 3D yüzeyi
        // hem harita overlay'ini günceller; redrawMapOverlay zaten kendi içinde polygon
        // sınırını da çiziyor (map.clear() + drawPolygonOnMap).
        applyFilterAndRefresh()

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

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }
}
