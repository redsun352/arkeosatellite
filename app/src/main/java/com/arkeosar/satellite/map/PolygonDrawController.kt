package com.arkeosar.satellite.map

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
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
 * Kullanıcının haritaya dokunarak taranacak alanı (polygon) çizmesini yönetir.
 *
 * Davranış:
 *  - Her dokunuş yeni bir köşe noktası ekler, noktalar bir çizgiyle (polyline) birbirine bağlanır.
 *  - İlk noktaya yeterince yakın bir dokunuş (CLOSE_THRESHOLD_METERS içinde) polygonu kapatır:
 *    polyline kaldırılır, dolu/yarı saydam bir Polygon çizilir.
 *  - Polygon kapandıktan sonra yeni dokunuşlar kabul edilmez; kullanıcı önce temizlemeli/geri almalı.
 */
class PolygonDrawController(
    private val map: GoogleMap,
    private val onPolygonChanged: (ScanPolygon?) -> Unit
) {
    companion object {
        private const val CLOSE_THRESHOLD_METERS = 25.0
    }

    private val rawPoints = mutableListOf<LatLng>()
    private val markers = mutableListOf<Marker>()
    private var activePolyline: Polyline? = null
    private var closedPolygon: Polygon? = null
    private var isClosed = false

    init {
        map.setOnMapClickListener { latLng -> onMapTapped(latLng) }
    }

    private fun onMapTapped(latLng: LatLng) {
        if (isClosed) return // kapalı polygon üstüne yeni nokta eklenmez, önce clear/undo gerekir

        // İlk noktaya yakın dokunuş -> polygonu kapat
        if (rawPoints.size >= 3 && distanceMeters(rawPoints.first(), latLng) <= CLOSE_THRESHOLD_METERS) {
            closePolygon()
            return
        }

        addPoint(latLng)
    }

    private fun addPoint(latLng: LatLng) {
        rawPoints.add(latLng)

        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .anchor(0.5f, 0.5f)
        )
        marker?.let { markers.add(it) }

        redrawPolyline()
        notifyChanged()
    }

    /** Son eklenen noktayı geri alır. */
    fun undoLastPoint() {
        if (isClosed) {
            // Kapalı polygonu açıp düzenlemeye devam etmek istersek diye - şimdilik basitçe temizle
            reopenPolygon()
            return
        }
        if (rawPoints.isEmpty()) return

        rawPoints.removeAt(rawPoints.lastIndex)
        markers.removeAt(markers.lastIndex).remove()
        redrawPolyline()
        notifyChanged()
    }

    /** Tüm noktaları ve çizimi temizler. */
    fun clear() {
        rawPoints.clear()
        markers.forEach { it.remove() }
        markers.clear()
        activePolyline?.remove()
        activePolyline = null
        closedPolygon?.remove()
        closedPolygon = null
        isClosed = false
        notifyChanged()
    }

    private fun closePolygon() {
        activePolyline?.remove()
        activePolyline = null

        closedPolygon = map.addPolygon(
            PolygonOptions()
                .addAll(rawPoints)
                .strokeColor(0xFF1B5E20.toInt())
                .strokeWidth(4f)
                .fillColor(0x331B5E20)
        )
        isClosed = true
        notifyChanged()
    }

    private fun reopenPolygon() {
        closedPolygon?.remove()
        closedPolygon = null
        isClosed = false
        redrawPolyline()
        notifyChanged()
    }

    private fun redrawPolyline() {
        activePolyline?.remove()
        if (rawPoints.size >= 2) {
            activePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(rawPoints)
                    .color(0xFFFF8F00.toInt())
                    .width(4f)
            )
        } else {
            activePolyline = null
        }
    }

    private fun notifyChanged() {
        val result = if (isClosed && rawPoints.size >= 3) {
            ScanPolygon(rawPoints.map { LatLngPoint(it.latitude, it.longitude) })
        } else {
            null
        }
        onPolygonChanged(result)
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }
}
