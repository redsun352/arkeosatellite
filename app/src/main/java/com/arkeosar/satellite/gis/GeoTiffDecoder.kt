package com.arkeosar.satellite.gis

import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.network.BandRaster
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal TIFF/GeoTIFF okuyucu.
 *
 * Sentinel Hub Process API ve benzeri servisler, basit isteklerde genellikle
 * tek bant, sıkıştırılmamış (veya çok basit run-length tipte), "striped"
 * (tiled olmayan) TIFF dosyaları döndürür. Bu sınıf TAM bir GeoTIFF/TIFF
 * implementasyonu DEĞİLDİR - sadece bu projede ihtiyaç duyulan dar formatı
 * (tek bant, FLOAT32 veya UINT16, sıkıştırma yok) okur.
 *
 * Daha karmaşık TIFF'ler (tiled, LZW/Deflate sıkıştırmalı, çok bantlı) için
 * bu decoder İstisna fırlatır - böyle bir durumda Process API isteğinde
 * "compression: NONE" zorlanmalı veya gerçek bir TIFF kütüphanesi entegre
 * edilmeli (bkz. yorum: GeoTools/libtiff Android portları ağır bağımlılıklar
 * gerektirir, bu yüzden bilinçli olarak basitleştirilmiş bir yaklaşım seçildi -
 * tıpkı .v3d/.g3d format decode'unda yapıldığı gibi: sadece gerekli alt küme).
 *
 * TIFF IFD (Image File Directory) yapısı hakkında kısa not:
 *  - Dosya 8 byte header ile başlar: byte order (II/MM) + magic (42) + ilk IFD offset
 *  - Her IFD: 2 byte entry sayısı + (entry sayısı * 12 byte) tag kayıtları + 4 byte next IFD offset
 *  - Her tag kaydı: tag id (2B) + field type (2B) + value count (4B) + value/offset (4B)
 */
object GeoTiffDecoder {

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_HEIGHT = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_SAMPLES_PER_PIXEL = 277

    private data class IfdEntry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int)

    fun decodeSingleBand(tiffBytes: ByteArray, bbox: BoundingBox, bandName: String): BandRaster {
        val buffer = ByteBuffer.wrap(tiffBytes)

        // --- Header: byte order tespiti ---
        val b0 = tiffBytes[0].toInt() and 0xFF
        val b1 = tiffBytes[1].toInt() and 0xFF
        buffer.order(
            when {
                b0 == 0x49 && b1 == 0x49 -> ByteOrder.LITTLE_ENDIAN // "II"
                b0 == 0x4D && b1 == 0x4D -> ByteOrder.BIG_ENDIAN    // "MM"
                else -> throw IllegalArgumentException("Geçersiz TIFF header (byte order işareti bulunamadı)")
            }
        )

        val magic = buffer.getShort(2).toInt() and 0xFFFF
        require(magic == 42) { "Geçersiz TIFF magic number: $magic (42 bekleniyor)" }

        val firstIfdOffset = buffer.getInt(4)
        val entries = readIfd(buffer, firstIfdOffset)

        val width = entries.find(TAG_IMAGE_WIDTH)?.valueOrOffset
            ?: throw IllegalStateException("TIFF'te ImageWidth tag'i yok")
        val height = entries.find(TAG_IMAGE_HEIGHT)?.valueOrOffset
            ?: throw IllegalStateException("TIFF'te ImageHeight tag'i yok")
        val bitsPerSample = entries.find(TAG_BITS_PER_SAMPLE)?.valueOrOffset ?: 32
        val compression = entries.find(TAG_COMPRESSION)?.valueOrOffset ?: 1
        val sampleFormat = entries.find(TAG_SAMPLE_FORMAT)?.valueOrOffset ?: 3 // 3 = IEEE float varsayım
        val samplesPerPixel = entries.find(TAG_SAMPLES_PER_PIXEL)?.valueOrOffset ?: 1
        val rowsPerStrip = entries.find(TAG_ROWS_PER_STRIP)?.valueOrOffset ?: height

        require(compression == 1) {
            "Desteklenmeyen TIFF sıkıştırması: $compression. Bu decoder sadece sıkıştırılmamış " +
                "(compression=1) TIFF okuyabilir. Process API isteğinde sıkıştırmasız format istenmeli."
        }
        require(samplesPerPixel == 1) {
            "Desteklenmeyen bant sayısı: $samplesPerPixel. Bu decoder tek bant TIFF okur."
        }

        val stripOffsetsEntry = entries.find(TAG_STRIP_OFFSETS)
            ?: throw IllegalStateException("TIFF'te StripOffsets tag'i yok (tiled TIFF desteklenmiyor)")
        val stripByteCountsEntry = entries.find(TAG_STRIP_BYTE_COUNTS)
            ?: throw IllegalStateException("TIFF'te StripByteCounts tag'i yok")

        val stripOffsets = readValueArray(buffer, stripOffsetsEntry)
        val stripByteCounts = readValueArray(buffer, stripByteCountsEntry)

        val values = FloatArray(width * height)
        var rowCursor = 0

        for (stripIndex in stripOffsets.indices) {
            val offset = stripOffsets[stripIndex]
            val byteCount = stripByteCounts[stripIndex]
            val rowsInThisStrip = minOf(rowsPerStrip, height - rowCursor)
            val samplesInStrip = rowsInThisStrip * width

            val stripBuffer = ByteBuffer.wrap(tiffBytes, offset, byteCount).order(buffer.order())
            val stripStartIndex = rowCursor * width

            for (i in 0 until samplesInStrip) {
                val value = when {
                    bitsPerSample == 32 && sampleFormat == 3 -> stripBuffer.float       // FLOAT32
                    bitsPerSample == 16 && sampleFormat == 1 -> (stripBuffer.short.toInt() and 0xFFFF).toFloat() // UINT16
                    bitsPerSample == 8 && sampleFormat == 1 -> (stripBuffer.get().toInt() and 0xFF).toFloat()    // UINT8
                    else -> throw IllegalStateException(
                        "Desteklenmeyen örnek formatı: bitsPerSample=$bitsPerSample sampleFormat=$sampleFormat"
                    )
                }
                // stripBuffer sıralı (sequential) okunuyor; i, strip içindeki düz (flat) konum.
                // Global pozisyon = stripStartIndex (önceki striplerden gelen satır offseti) + i.
                values[stripStartIndex + i] = value
            }
            rowCursor += rowsInThisStrip
        }

        return BandRaster(
            bbox = bbox,
            widthPx = width,
            heightPx = height,
            values = values,
            bandName = bandName
        )
    }

    private fun readIfd(buffer: ByteBuffer, offset: Int): List<IfdEntry> {
        val entryCount = buffer.getShort(offset).toInt() and 0xFFFF
        val entries = mutableListOf<IfdEntry>()
        var cursor = offset + 2
        repeat(entryCount) {
            val tag = buffer.getShort(cursor).toInt() and 0xFFFF
            val type = buffer.getShort(cursor + 2).toInt() and 0xFFFF
            val count = buffer.getInt(cursor + 4)
            // TIFF spec: count==1 ve type SHORT(3) ise değer, 4 byte'lık value alanının
            // İLK 2 byte'ındadır (little-endian'da), kalan 2 byte tanımsız/padding olabilir.
            // Bunu 4 byte int olarak okumak padding'in sıfır olduğunu varsayar - bu garanti
            // değildir, dolayısıyla type'a göre doğru genişlikte okuyoruz.
            val valueOrOffset = when {
                count == 1 && type == 3 -> buffer.getShort(cursor + 8).toInt() and 0xFFFF // SHORT
                else -> buffer.getInt(cursor + 8) // LONG, ya da count>1 (offset her durumda 4 byte)
            }
            entries.add(IfdEntry(tag, type, count, valueOrOffset))
            cursor += 12
        }
        return entries
    }

    private fun List<IfdEntry>.find(tag: Int): IfdEntry? = firstOrNull { it.tag == tag }

    /**
     * StripOffsets/StripByteCounts gibi tag'ler, count>1 olduğunda value alanı
     * doğrudan değer değil, değerlerin saklandığı yere bir offset içerir.
     * count==1 ise value alanı doğrudan değerin kendisidir (TIFF'in "inline value" optimizasyonu).
     */
    private fun readValueArray(buffer: ByteBuffer, entry: IfdEntry): IntArray {
        if (entry.count == 1) return intArrayOf(entry.valueOrOffset)

        val result = IntArray(entry.count)
        val bytesPerValue = when (entry.type) {
            3 -> 2 // SHORT
            4 -> 4 // LONG
            else -> throw IllegalStateException("Desteklenmeyen IFD value tipi: ${entry.type}")
        }
        var cursor = entry.valueOrOffset
        for (i in 0 until entry.count) {
            result[i] = if (bytesPerValue == 2) {
                buffer.getShort(cursor).toInt() and 0xFFFF
            } else {
                buffer.getInt(cursor)
            }
            cursor += bytesPerValue
        }
        return result
    }
}
