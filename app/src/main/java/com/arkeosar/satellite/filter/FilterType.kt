package com.arkeosar.satellite.filter

/**
 * Surfer'ın Grid > Filter menüsündekine karşılık gelen, anomali skor grid'ine
 * (HeightmapGrid) uygulanabilecek filtreler. Hepsi aynı imzayı paylaşır:
 * FloatArray (width*height, satır-major) -> FloatArray (aynı boyut).
 */
enum class FilterType(val label: String) {
    NONE("Ham Veri (Filtresiz)"),
    GAUSSIAN("Gaussian Filter"),
    MEDIAN("Median Filter"),
    ADAPTIVE("Adaptive Filter"),
    LOW_PASS("Low Pass"),
    HIGH_PASS("High Pass"),
    BAND_PASS("Band Pass"),
    BAND_STOP("Band Stop"),
    GRADIENT("Gradient"),
    LAPLACIAN("Laplacian"),
    EDGE_ENHANCEMENT("Edge Enhancement"),
    CONTRAST_ENHANCEMENT("Contrast Enhancement"),
    NOISE_REMOVAL("Noise Removal"),
    HISTOGRAM_EQUALIZATION("Histogram Equalization"),
    LOCAL_CONTRAST("Local Contrast"),
    ANOMALY_ENHANCEMENT("Anomaly Enhancement"),
}
