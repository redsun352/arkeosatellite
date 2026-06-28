package com.arkeosar.satellite.map

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.arkeosar.satellite.model.LatLngPoint
import com.arkeosar.satellite.model.ScanPolygon

/**
 * Kullanıcının haritaya dokunarak taranacak alanı çizmesini yönetir.
 * Üç aracı destekler:
 *
 *  - POLYGON: Her dokunuş yeni bir köşe noktası ekler. İlk noktaya yeterince yakın bir
 *    dokunuş (CLOSE_THRESHOLD_METERS içinde, en az 3 nokta varken) polygonu kapatır.
 *  - RECTANGLE: İlk dokunuş bir köşeyi, ikinci dokunuş karşı köşeyi belirler - aradan
 *    enlem/boylam hizalı bir dikdörtgen (4 köşeli polygon) üretilir.
 *  - CIRCLE: İlk dokunuş merkezi, ikinci dokunuş yarıçapı belirler - sonuç, çok kenarlı
 *    bir polygon (32 nokta) olarak ScanPolygon'a çevrilir, böylece tüm downstream kod
 *    (AnalysisOrchestrator, PolyUtil.containsLocation) tek bir veri modeliyle çalışabilir.
 *
 * Tüm araçlar sonunda aynı çıktıyı (ScanPolygon) üretir - analiz/orchestration katmanı
 * hangi aracın kullanıldığını bilmesine gerek kalmaz.
 */
class ShapeDrawController(
    private val map: GoogleMap,
    private val onPolygonChanged: (ScanPolygon?) -> Unit
) {
    companion object {
        private const val CLOSE_THRESHOLD_METERS = 40.0 // yedek mekanizma; asıl kapama "Bitir" butonuyla yapılır
        private const val CIRCLE_POLYGON_SIDES = 32
    }

    var currentTool: DrawTool = DrawTool.POLYGON
        private set

    // --- Polygon state ---
    private val polygonPoints = mutableListOf<LatLng>()
    private val polygonMarkers = mutableListOf<Marker>()
    private var activePolyline: Polyline? = null

    // --- Rectangle/Circle state (iki dokunuşlu araçlar için ortak) ---
    private var firstTapPoint: LatLng? = null
    private var firstTapMarker: Marker? = null
    private var previewCircle: Circle? = null

    // --- Sonuç görselleri (hangi araç kullanıldıysa onun çizimi) ---
    private var resultPolygon: Polygon? = null
    private var isClosed = false

    init {
        map.setOnMapClickListener { latLng -> onMapTapped(latLng) }
    }

    /** Aktif çizim aracını değiştirir. Yarım kalmış bir çizim varsa temizler. */
    fun setTool(tool: DrawTool) {
        if (currentTool == tool) return
        clear()
        currentTool = tool
    }

    private fun onMapTapped(latLng: LatLng) {
        if (isClosed) return // kapalı şekil üstüne yeni nokta eklenmez, önce clear gerekir

        when (currentTool) {
            DrawTool.POLYGON -> onPolygonTap(latLng)
            DrawTool.RECTANGLE -> onTwoTapShapeTap(latLng) { p1, p2 -> buildRectanglePoints(p1, p2) }
            DrawTool.CIRCLE -> onCircleTap(latLng)
        }
    }

    // ---------- POLYGON ----------

    private fun onPolygonTap(latLng: LatLng) {
        // İlk noktaya yakın dokunuşla kapatma hâlâ desteklenir (alışkın kullanıcılar için),
        // ama asıl güvenilir yol artık finishPolygon() - bir "Bitir" butonuna bağlanır.
        if (polygonPoints.size >= 3 && distanceMeters(polygonPoints.first(), latLng) <= CLOSE_THRESHOLD_METERS) {
            finalizePolygon(polygonPoints.toList())
            return
        }

        polygonPoints.add(latLng)
        val marker = map.addMarker(
            MarkerOptions().position(latLng).icon(scanPointIcon()).anchor(0.5f, 0.5f)
        )
        marker?.let { polygonMarkers.add(it) }

        activePolyline?.remove()
        activePolyline = if (polygonPoints.size >= 2) {
            map.addPolyline(PolylineOptions().addAll(polygonPoints).color(0xFFFF8A3D.toInt()).width(4f))
        } else null

        // Henüz kapanmadı (null) ama "Bitir" butonunun enabled durumu değişmiş olabilir -
        // MainActivity bu callback'i her çağrıldığında canFinishPolygon()'u tekrar okur.
        onPolygonChanged(null)
    }

    /**
     * Mevcut polygon noktalarını kapatır - ilk noktaya tekrar dokunmaya gerek kalmadan.
     * Sadece POLYGON aracı aktifken ve en az 3 nokta varken bir şey yapar; aksi halde
     * sessizce hiçbir şey yapmaz (UI tarafı, butonun enabled durumunu ayrıca kontrol eder).
     */
    fun finishPolygon() {
        if (currentTool != DrawTool.POLYGON) return
        if (polygonPoints.size < 3) return
        finalizePolygon(polygonPoints.toList())
    }

    /** Polygon modunda, kapatma için "Bitir" butonunun aktif edilip edilemeyeceğini bildirir. */
    fun canFinishPolygon(): Boolean = currentTool == DrawTool.POLYGON && polygonPoints.size >= 3 && !isClosed

    // ---------- RECTANGLE (iki dokunuşlu genel akış) ----------

    private fun onTwoTapShapeTap(latLng: LatLng, buildPoints: (LatLng, LatLng) -> List<LatLng>) {
        val first = firstTapPoint
        if (first == null) {
            firstTapPoint = latLng
            firstTapMarker = map.addMarker(MarkerOptions().position(latLng).icon(scanPointIcon()).anchor(0.5f, 0.5f))
            return
        }
        finalizePolygon(buildPoints(first, latLng))
    }

    private fun buildRectanglePoints(corner1: LatLng, corner2: LatLng): List<LatLng> {
        val minLat = minOf(corner1.latitude, corner2.latitude)
        val maxLat = maxOf(corner1.latitude, corner2.latitude)
        val minLng = minOf(corner1.longitude, corner2.longitude)
        val maxLng = maxOf(corner1.longitude, corner2.longitude)
        return listOf(
            LatLng(maxLat, minLng),
            LatLng(maxLat, maxLng),
            LatLng(minLat, maxLng),
            LatLng(minLat, minLng)
        )
    }

    // ---------- CIRCLE ----------

    private fun onCircleTap(latLng: LatLng) {
        val center = firstTapPoint
        if (center == null) {
            firstTapPoint = latLng
            firstTapMarker = map.addMarker(MarkerOptions().position(latLng).icon(scanPointIcon()).anchor(0.5f, 0.5f))
            return
        }

        val radiusMeters = distanceMeters(center, latLng)
        val points = buildCirclePolygonPoints(center, radiusMeters)
        finalizePolygon(points)
    }

    /** Daireyi CIRCLE_POLYGON_SIDES kenarlı bir polygon olarak yaklaştırır. */
    private fun buildCirclePolygonPoints(center: LatLng, radiusMeters: Double): List<LatLng> {
        val points = mutableListOf<LatLng>()
        val earthRadius = 6371000.0 // metre
        for (i in 0 until CIRCLE_POLYGON_SIDES) {
            val angle = 2.0 * Math.PI * i / CIRCLE_POLYGON_SIDES
            val angularDistance = radiusMeters / earthRadius

            val lat1 = Math.toRadians(center.latitude)
            val lng1 = Math.toRadians(center.longitude)

            val lat2 = Math.asin(
                Math.sin(lat1) * Math.cos(angularDistance) +
                    Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(angle)
            )
            val lng2 = lng1 + Math.atan2(
                Math.sin(angle) * Math.sin(angularDistance) * Math.cos(lat1),
                Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
            )

            points.add(LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2)))
        }
        return points
    }

    // ---------- Ortak finalize/clear ----------

    private fun finalizePolygon(points: List<LatLng>) {
        activePolyline?.remove()
        activePolyline = null
        firstTapMarker?.remove()
        firstTapMarker = null
        previewCircle?.remove()
        previewCircle = null

        resultPolygon = map.addPolygon(
            PolygonOptions()
                .addAll(points)
                .strokeColor(0xFF39D98A.toInt())
                .strokeWidth(4f)
                .fillColor(0x3339D98A)
        )
        // Polygon çizim sırasında eklenen ara marker'ları temizle (görsel sadelik için);
        // sonuç şekli artık resultPolygon ile gösteriliyor.
        polygonMarkers.forEach { it.remove() }
        polygonMarkers.clear()

        isClosed = true
        onPolygonChanged(ScanPolygon(points.map { LatLngPoint(it.latitude, it.longitude) }))
    }

    /** Son adımı geri alır (polygon'da son nokta, rectangle/circle'da ilk dokunuşu temizler). */
    fun undoLastPoint() {
        if (isClosed) {
            clear()
            return
        }
        when (currentTool) {
            DrawTool.POLYGON -> {
                if (polygonPoints.isEmpty()) return
                polygonPoints.removeAt(polygonPoints.lastIndex)
                polygonMarkers.removeAt(polygonMarkers.lastIndex).remove()
                activePolyline?.remove()
                activePolyline = if (polygonPoints.size >= 2) {
                    map.addPolyline(PolylineOptions().addAll(polygonPoints).color(0xFFFF8A3D.toInt()).width(4f))
                } else null
                onPolygonChanged(null)
            }
            DrawTool.RECTANGLE, DrawTool.CIRCLE -> {
                firstTapMarker?.remove()
                firstTapMarker = null
                firstTapPoint = null
            }
        }
    }

    /** Tüm noktaları ve çizimi temizler. */
    fun clear() {
        polygonPoints.clear()
        polygonMarkers.forEach { it.remove() }
        polygonMarkers.clear()
        activePolyline?.remove()
        activePolyline = null
        firstTapMarker?.remove()
        firstTapMarker = null
        firstTapPoint = null
        previewCircle?.remove()
        previewCircle = null
        resultPolygon?.remove()
        resultPolygon = null
        isClosed = false
        onPolygonChanged(null)
    }

    private fun scanPointIcon() =
        com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(
            com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_GREEN
        )

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }
}
