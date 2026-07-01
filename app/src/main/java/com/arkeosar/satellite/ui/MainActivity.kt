package com.arkeosar.satellite.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.arkeosar.satellite.R
import com.arkeosar.satellite.databinding.ActivityMainBinding
import com.arkeosar.satellite.databinding.BottomsheetLayersBinding
import com.arkeosar.satellite.gis.AnalysisOrchestrator
import com.arkeosar.satellite.map.DrawTool
import com.arkeosar.satellite.map.ShapeDrawController
import com.arkeosar.satellite.model.ScanPolygon
import com.arkeosar.satellite.network.DateRange
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.copernicus.CopernicusDataSource
import com.arkeosar.satellite.network.planet.PlanetDataSource
import com.arkeosar.satellite.network.usgs.UsgsDataSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Kullanıcının seçebileceği mevsim presetleri - crop-mark tespiti mevsime bağımlıdır. */
private enum class SeasonPreset { SUMMER_DROUGHT, SPRING, RECENT_30_DAYS }

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var shapeController: ShapeDrawController
    private var currentPolygon: ScanPolygon? = null

    // Kullanıcının katman seçici bottom sheet'ten seçtiği kaynaklar - varsayılan
    // olarak Sentinel-2 ve Planet açık, USGS kapalı (henüz "download" izni onay bekliyor).
    private var sentinel2Enabled = true
    private var planetEnabled = true
    private var usgsEnabled = false
    private var seasonPreset = SeasonPreset.SUMMER_DROUGHT

    private val sources: List<SatelliteDataSource>
        get() = buildList {
            if (sentinel2Enabled) add(CopernicusDataSource())
            if (planetEnabled) add(PlanetDataSource())
            if (usgsEnabled) add(UsgsDataSource())
        }

    /**
     * Seçili mevsim presetine göre mutlak bir DateRange üretir. "Bu yıl" varsayımı
     * kullanılır - eğer seçilen mevsim henüz gelmediyse (örn. Ocak ayında "yaz kuraklığı"
     * seçilirse), geçen yılın aynı dönemi kullanılır, çünkü gelecekteki bir tarih için
     * uydu verisi mevcut olamaz.
     */
    private fun resolveDateRange(): DateRange {
        val today = LocalDate.now()
        return when (seasonPreset) {
            SeasonPreset.RECENT_30_DAYS -> DateRange(from = today.minusDays(30), to = today)
            SeasonPreset.SUMMER_DROUGHT -> {
                var from = LocalDate.of(today.year, 6, 1)
                var to = LocalDate.of(today.year, 8, 31)
                if (to.isAfter(today)) { // bu yılın yazı henüz gelmedi/bitmedi -> geçen yılı kullan
                    from = from.minusYears(1)
                    to = to.minusYears(1)
                }
                DateRange(from = from, to = minOf(to, today))
            }
            SeasonPreset.SPRING -> {
                var from = LocalDate.of(today.year, 3, 1)
                var to = LocalDate.of(today.year, 4, 30)
                if (to.isAfter(today)) {
                    from = from.minusYears(1)
                    to = to.minusYears(1)
                }
                DateRange(from = from, to = minOf(to, today))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        binding.btnClear.setOnClickListener { shapeController.clear() }
        binding.btnUndo.setOnClickListener {
            shapeController.undoLastPoint()
            updateFinishButtonState()
        }
        binding.btnFinishPolygon.setOnClickListener {
            shapeController.finishPolygon()
        }
        binding.btnRunAnalysis.setOnClickListener {
            currentPolygon?.let { polygon -> runAnalysis(polygon) }
        }

        binding.toolPolygon.setOnClickListener { selectTool(DrawTool.POLYGON) }
        binding.toolRectangle.setOnClickListener { selectTool(DrawTool.RECTANGLE) }
        binding.toolCircle.setOnClickListener { selectTool(DrawTool.CIRCLE) }
        binding.toolLayers.setOnClickListener { showLayerSelector() }
    }

    /**
     * Üstteki talimat metni durum çubuğuyla (status bar), alttaki kontrol paneli
     * de navigasyon çubuğuyla (navigation bar / gesture bar) çakışıyordu - çünkü
     * layout tüm ekranı (edge-to-edge) kaplıyor ama sistem çubuklarının kapladığı
     * alan için padding ayrılmamıştı. WindowInsets üzerinden bu alanları runtime'da
     * öğrenip ilgili view'lara padding olarak ekliyoruz.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBarInsets.top + view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlPanel) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }
    }

    override fun onMapReady(map: GoogleMap) {
        // Arkeolojik tarama için uydu görüntüsü (etiketlerle birlikte) en kullanışlı
        // varsayılan - kullanıcı toggle butonuyla normal harita görünümüne dönebilir.
        map.mapType = GoogleMap.MAP_TYPE_HYBRID

        // Varsayılan kamera konumu: Kayseri civarı (Hasan'ın çalıştığı bölge)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(38.514, 35.786), 14f))

        shapeController = ShapeDrawController(map) { polygon ->
            currentPolygon = polygon
            binding.btnRunAnalysis.isEnabled = polygon != null
            updateFinishButtonState()
        }
        updateToolUi(DrawTool.POLYGON)

        binding.toolMapType.setOnClickListener {
            map.mapType = if (map.mapType == GoogleMap.MAP_TYPE_HYBRID) {
                GoogleMap.MAP_TYPE_NORMAL
            } else {
                GoogleMap.MAP_TYPE_HYBRID
            }
        }
    }

    private fun updateFinishButtonState() {
        if (::shapeController.isInitialized) {
            binding.btnFinishPolygon.isEnabled = shapeController.canFinishPolygon()
        }
    }

    private fun selectTool(tool: DrawTool) {
        if (!::shapeController.isInitialized) return
        shapeController.setTool(tool)
        currentPolygon = null
        binding.btnRunAnalysis.isEnabled = false
        updateFinishButtonState()
        updateToolUi(tool)
    }

    /** Aktif araca göre üst başlık metnini, talimat metnini ve FAB renklerini günceller. */
    private fun updateToolUi(tool: DrawTool) {
        val (titleRes, instructionRes) = when (tool) {
            DrawTool.POLYGON -> R.string.tool_polygon_title to R.string.instructions_draw_polygon
            DrawTool.RECTANGLE -> R.string.tool_rectangle_title to R.string.instructions_draw_rectangle
            DrawTool.CIRCLE -> R.string.tool_circle_title to R.string.instructions_draw_circle
        }
        binding.toolNameText.setText(titleRes)
        binding.instructionText.setText(instructionRes)

        val activeColor = getColor(R.color.accent_scan)
        val inactiveColor = getColor(R.color.bg_surface_elevated)
        binding.toolPolygon.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (tool == DrawTool.POLYGON) activeColor else inactiveColor
        )
        binding.toolRectangle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (tool == DrawTool.RECTANGLE) activeColor else inactiveColor
        )
        binding.toolCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (tool == DrawTool.CIRCLE) activeColor else inactiveColor
        )
    }

    /** Uydu kaynağı ve mevsim seçici bottom sheet'i gösterir. */
    private fun showLayerSelector() {
        val sheetBinding = BottomsheetLayersBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.checkSentinel2.isChecked = sentinel2Enabled
        sheetBinding.checkPlanet.isChecked = planetEnabled
        sheetBinding.checkUsgs.isChecked = usgsEnabled

        when (seasonPreset) {
            SeasonPreset.SUMMER_DROUGHT -> sheetBinding.radioSeasonSummer.isChecked = true
            SeasonPreset.SPRING -> sheetBinding.radioSeasonSpring.isChecked = true
            SeasonPreset.RECENT_30_DAYS -> sheetBinding.radioSeasonRecent.isChecked = true
        }

        sheetBinding.btnApplyLayers.setOnClickListener {
            val anySelected = sheetBinding.checkSentinel2.isChecked ||
                sheetBinding.checkPlanet.isChecked ||
                sheetBinding.checkUsgs.isChecked

            if (!anySelected) {
                showStatus(getString(R.string.error_no_layer_selected))
                return@setOnClickListener
            }

            sentinel2Enabled = sheetBinding.checkSentinel2.isChecked
            planetEnabled = sheetBinding.checkPlanet.isChecked
            usgsEnabled = sheetBinding.checkUsgs.isChecked

            seasonPreset = when (sheetBinding.radioGroupSeason.checkedRadioButtonId) {
                sheetBinding.radioSeasonSpring.id -> SeasonPreset.SPRING
                sheetBinding.radioSeasonRecent.id -> SeasonPreset.RECENT_30_DAYS
                else -> SeasonPreset.SUMMER_DROUGHT
            }

            dialog.dismiss()
        }

        dialog.show()
    }

    private fun runAnalysis(polygon: ScanPolygon) {
        val areaKm2 = polygon.approxAreaKm2()
        if (areaKm2 > 25.0) {
            showStatus(getString(R.string.error_polygon_too_large))
            return
        }

        setLoading(true)
        showStatus(getString(R.string.status_fetching_satellite_data))

        lifecycleScope.launch {
            try {
                val dateRange = resolveDateRange()
                val orchestrator = AnalysisOrchestrator(sources)
                val result = orchestrator.analyze(polygon, dateRange)

                showStatus(getString(R.string.status_done))
                val failedSourcesText = result.failedSources.joinToString("\n") { (name, msg) -> "$name: $msg" }

                // Intent boyutu sınırlı olduğu için TÜM hücreleri taşımak güvenli değil -
                // sonuç ekranında görsel bir özet için en yüksek skorlu N hücreyi örnekliyoruz.
                val topCells = result.cells.sortedByDescending { it.score }.take(500)

                val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                    putExtra(ResultActivity.EXTRA_CELL_COUNT, result.cells.size)
                    putExtra(ResultActivity.EXTRA_SOURCES, result.sourcesUsed.joinToString(", ") { it.displayName })
                    putExtra(ResultActivity.EXTRA_FAILED_SOURCES, failedSourcesText)
                    putExtra(ResultActivity.EXTRA_POLYGON_LATS, polygon.points.map { it.lat }.toDoubleArray())
                    putExtra(ResultActivity.EXTRA_POLYGON_LNGS, polygon.points.map { it.lng }.toDoubleArray())
                    putExtra(ResultActivity.EXTRA_CELL_LATS, topCells.map { it.lat }.toDoubleArray())
                    putExtra(ResultActivity.EXTRA_CELL_LNGS, topCells.map { it.lng }.toDoubleArray())
                    putExtra(ResultActivity.EXTRA_CELL_SCORES, topCells.map { it.score }.toDoubleArray())
                    val polygonBbox = polygon.boundingBox()
                    putExtra(ResultActivity.EXTRA_BBOX_MIN_LAT, polygonBbox.minLat)
                    putExtra(ResultActivity.EXTRA_BBOX_MAX_LAT, polygonBbox.maxLat)
                    putExtra(ResultActivity.EXTRA_BBOX_MIN_LNG, polygonBbox.minLng)
                    putExtra(ResultActivity.EXTRA_BBOX_MAX_LNG, polygonBbox.maxLng)
                    result.heightmap?.let { hm ->
                        putExtra(ResultActivity.EXTRA_HEIGHTMAP_WIDTH, hm.width)
                        putExtra(ResultActivity.EXTRA_HEIGHTMAP_HEIGHT, hm.height)
                        putExtra(ResultActivity.EXTRA_HEIGHTMAP_SCORES, hm.scores)
                        hm.rawDem?.let { putExtra(ResultActivity.EXTRA_HEIGHTMAP_DEM, it) }
                        hm.rawNdvi?.let { putExtra(ResultActivity.EXTRA_HEIGHTMAP_NDVI, it) }
                        hm.rawNdwi?.let { putExtra(ResultActivity.EXTRA_HEIGHTMAP_NDWI, it) }
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                showStatus(getString(R.string.status_error, e.message ?: "bilinmeyen hata"))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRunAnalysis.isEnabled = !loading && currentPolygon != null
        binding.btnClear.isEnabled = !loading
        binding.btnUndo.isEnabled = !loading
    }

    private fun showStatus(message: String) {
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = message
    }
}
