package com.arkeosar.satellite.map

/** Haritada taranacak alanı çizmek için kullanılabilecek araçlar. */
enum class DrawTool(val label: String) {
    POLYGON("Polygon"),
    RECTANGLE("Dikdörtgen"),
    CIRCLE("Daire");
}
