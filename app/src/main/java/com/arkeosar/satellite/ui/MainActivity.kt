package com.arkeosar.satellite.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arkeosar.satellite.R
import com.arkeosar.satellite.databinding.ActivityMainBinding
import com.arkeosar.satellite.gis.AnalysisOrchestrator
import com.arkeosar.satellite.map.PolygonDrawController
import com.arkeosar.satellite.model.ScanPolygon
import com.arkeosar.satellite.network.SatelliteDataSource
import com.arkeosar.satellite.network.copernicus.CopernicusDataSource
import com.arkeosar.satellite.network.usgs.UsgsDataSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var polygonController: PolygonDrawController
    private var currentPolygon: ScanPolygon? = null

    // V1: PlanetDataSource activate/poll akışı asset bulunabilirliğine bağımlı olduğu için
    // ve henüz gerçek bir hesapla test edilmediği için varsayılan kaynak listesine dahil
    // edilmedi. Copernicus (NDVI) + USGS (termal) ile başlanıyor.
    private val sources: List<SatelliteDataSource> by lazy {
        listOf(CopernicusDataSource(), UsgsDataSource())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        binding.btnClear.setOnClickListener {
            polygonController.clear()
        }
        binding.btnUndo.setOnClickListener {
            polygonController.undoLastPoint()
        }
        binding.btnRunAnalysis.setOnClickListener {
            currentPolygon?.let { polygon -> runAnalysis(polygon) }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        // Varsayılan kamera konumu: Kayseri civarı (Hasan'ın çalıştığı bölge)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(38.514, 35.786), 14f))

        polygonController = PolygonDrawController(map) { polygon ->
            currentPolygon = polygon
            binding.btnRunAnalysis.isEnabled = polygon != null
        }
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
                val orchestrator = AnalysisOrchestrator(sources)
                val result = orchestrator.analyze(polygon)

                showStatus(getString(R.string.status_done))
                val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                    putExtra(ResultActivity.EXTRA_CELL_COUNT, result.cells.size)
                    putExtra(ResultActivity.EXTRA_SOURCES, result.sourcesUsed.joinToString(", ") { it.displayName })
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
