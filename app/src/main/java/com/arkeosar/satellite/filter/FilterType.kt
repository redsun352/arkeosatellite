package com.arkeosar.satellite.filter

/**
 * Surfer'ın Grid > Filter menüsündekine karşılık gelen, anomali skor grid'ine
 * (HeightmapGrid) uygulanabilecek filtreler. Hepsi aynı imzayı paylaşır:
 * FloatArray (width*height, satır-major) -> FloatArray (aynı boyut).
 */
enum class FilterType(val label: String) {
    DETAILED("Detaylı Görünüm (Varsayılan)"),
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

    // --- Gelişmiş jeofizik filtreler (potansiyel alan/manyetik analizden uyarlanmış) ---
    ANALYTIC_SIGNAL("Analytic Signal"),
    TILT_DERIVATIVE("Tilt Derivative"),
    THETA_MAP("Theta Map"),
    TDX("TDX (Hyperbolic Tilt)"),
    TOTAL_HORIZONTAL_DERIVATIVE("Total Horizontal Derivative"),
    RX_ANOMALY_DETECTOR("RX Anomaly Detector"),
    MULTISCALE_BLOB("Multi-Scale Blob Detector (Otomatik Boyut)"),
    PCA_FUSION("PCA Veri Füzyonu (NDVI+NDWI)"),
    RX_MULTIBAND_GLOBAL("RX Detector - Çok Bantlı (Global)"),
    RX_MULTIBAND_LOCAL("RX Detector - Çok Bantlı (Yerel Pencere)"),

    // --- Jeolojik katman ayrıştırma (upward continuation tabanlı regional-residual separation) ---
    LAYER_SHALLOW("Katman: Yüzeysel"),
    LAYER_MEDIUM("Katman: Orta Derinlik"),
    LAYER_DEEP("Katman: Derin"),
    STRUCTURE_OUTLINE("Yapı Konturu (Kenar Çizgisi)"),
    GLCM_CONTRAST("Doku Kontrastı (GLCM)"),
    GLCM_HOMOGENEITY("Doku Homojenliği (GLCM)"),
    MORPHOLOGICAL_OPENING("Morfolojik Açma (Gürültü Temizleme)"),
    MORPHOLOGICAL_CLOSING("Morfolojik Kapama (Boşluk Doldurma)"),
    MORPHOLOGICAL_GRADIENT("Morfolojik Gradyan (Kalın Kenar)"),
    RIDGE_DETECTOR("Sırt/Koridor Dedektörü (Tünel-Koridor)"),
    STANDARD_DEVIATION("Standart Sapma (Yerel Değişkenlik)"),
    COMPASS_GRADIENT("Pusula Gradyanı (8 Yön)"),
    CONSENSUS_SCORE("Konsensüs Skoru (Çoklu Filtre Oylaması)"),
    WAVELET_DETAIL("Wavelet Detay Katsayısı (Haar)"),
    RBF_RESIDUAL("RBF Trend Çıkarma (Radyal Tabanlı Fonksiyon)"),
    KRIGING_RESIDUAL("Kriging Trend Çıkarma (Ordinary Kriging)"),
    NEAREST_NEIGHBOR("En Yakın Komşu (Hücresel/Mozaik Segmentasyon)"),
    NATURAL_NEIGHBOR("Doğal Komşu (Sibson/Delaunay İnterpolasyonu)"),
}
