package com.arkeosar.satellite.gis

import com.arkeosar.satellite.model.BoundingBox
import com.arkeosar.satellite.network.BandRaster
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Minimal TIFF/GeoTIFF okuyucu.
 *
 * Sentinel Hub Process API gibi servisler genellikle Deflate/ZLib sıkıştırmalı
 * (TIFF Compression tag değeri 8 veya 32946) tek bant GeoTIFF döndürür. Bu sınıf
 * TAM bir GeoTIFF/TIFF implementasyonu DEĞİLDİR - sadece bu projede ihtiyaç
 * duyulan dar formatı (tek bant, FLOAT32/UINT16/UINT8, sıkıştırmasız VEYA
 * Deflate/ZLib sıkıştırmalı, "striped" yani tiled olmayan) okur.
 *
 * Deflate/ZLib sıkıştırması, zlib'in (RFC1950) standart formatıdır - Java'nın
 * yerleşik java.util.zip.Inflater sınıfı bunu native olarak açabilir, harici
 * kütüphane gerekmez. ZLib (tag=8) ile Deflate (tag=32946) arasındaki tek fark
 * TIFF Compression tag değeridir; sıkıştırılmış veri formatı özdeştir.
 *
 * Predictor (tag 317) değeri 2 ise, sıkıştırma öncesi her satıra "horizontal
 * differencing" uygulanmıştır (her piksel, kendisinden önceki pikselle farkı
 * olarak saklanır) - bu durumda inflate ettikten sonra kümülatif toplama ile
 * geri alınması gerekir.
 *
 * Daha karmaşık TIFF'ler (tiled, LZW/JPEG sıkıştırmalı, çok bantlı) için
 * bu decoder İstisna fırlatır - gerçek bir TIFF kütüphanesi gerekirdi
 * (bkz. yorum: GeoTools/libtiff Android portları ağır bağımlılıklar
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
    private const val TAG_PREDICTOR = 317
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_SAMPLES_PER_PIXEL = 277

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_ZLIB = 8
    private const val COMPRESSION_ADOBE_DEFLATE = 32946

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
        val compression = entries.find(TAG_COMPRESSION)?.valueOrOffset ?: COMPRESSION_NONE
        val predictor = entries.find(TAG_PREDICTOR)?.valueOrOffset ?: 1
        val sampleFormat = entries.find(TAG_SAMPLE_FORMAT)?.valueOrOffset ?: 3 // 3 = IEEE float varsayım
        val samplesPerPixel = entries.find(TAG_SAMPLES_PER_PIXEL)?.valueOrOffset ?: 1
        val rowsPerStrip = entries.find(TAG_ROWS_PER_STRIP)?.valueOrOffset ?: height

        require(compression == COMPRESSION_NONE || compression == COMPRESSION_ZLIB || compression == COMPRESSION_ADOBE_DEFLATE) {
            "Desteklenmeyen TIFF sıkıştırması: $compression. Bu decoder sıkıştırmasız (1), " +
                "ZLib (8) veya Adobe Deflate (32946) okuyabilir."
        }
        require(samplesPerPixel == 1) {
            "Desteklenmeyen bant sayısı: $samplesPerPixel. Bu decoder tek bant TIFF okur."
        }
        require(predictor == 1 || predictor == 2 || predictor == 3) {
            "Desteklenmeyen predictor: $predictor. Bu decoder predictor 1 (yok), 2 (horizontal " +
                "differencing) veya 3 (floating point predictor) destekler."
        }

        val stripOffsetsEntry = entries.find(TAG_STRIP_OFFSETS)
            ?: throw IllegalStateException("TIFF'te StripOffsets tag'i yok (tiled TIFF desteklenmiyor)")
        val stripByteCountsEntry = entries.find(TAG_STRIP_BYTE_COUNTS)
            ?: throw IllegalStateException("TIFF'te StripByteCounts tag'i yok")

        val stripOffsets = readValueArray(buffer, stripOffsetsEntry)
        val stripByteCounts = readValueArray(buffer, stripByteCountsEntry)

        val bytesPerSample = bitsPerSample / 8
        val values = FloatArray(width * height)
        var rowCursor = 0

        for (stripIndex in stripOffsets.indices) {
            val offset = stripOffsets[stripIndex]
            val byteCount = stripByteCounts[stripIndex]
            val rowsInThisStrip = minOf(rowsPerStrip, height - rowCursor)
            val samplesInStrip = rowsInThisStrip * width

            // Strip'in ham (sıkıştırılmış olabilir) byte'larını al
            val rawStripBytes = tiffBytes.copyOfRange(offset, offset + byteCount)

            // Sıkıştırma varsa aç - ZLib/Deflate aynı formattır (RFC1950), tag değeri farklı.
            val decompressedBytes = if (compression == COMPRESSION_NONE) {
                rawStripBytes
            } else {
                inflateZlib(rawStripBytes, expectedSize = samplesInStrip * bytesPerSample)
            }

            // Predictor=2: integer horizontal differencing (sample seviyesinde kümülatif toplama).
            // Predictor=3: floating point predictor (byte-plane reorder + byte seviyesinde fark) -
            // bu ikisi FARKLI algoritmalardır, biri diğerinin integer/float versiyonu DEĞİLDİR.
            when (predictor) {
                2 -> applyHorizontalPredictorReversal(decompressedBytes, width, bytesPerSample, buffer.order())
                3 -> applyFloatingPointPredictorReversal(decompressedBytes, width, bytesPerSample, rowsInThisStrip)
            }

            val stripBuffer = ByteBuffer.wrap(decompressedBytes).order(buffer.order())
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

    /**
     * zlib (RFC1950) formatındaki veriyi açar. java.util.zip.Inflater varsayılan
     * olarak zlib header/trailer'ı bekler (nowrap=false), bu yüzden ekstra ayar gerekmez.
     */
    private fun inflateZlib(compressed: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val output = ByteArray(expectedSize)
        var totalRead = 0
        try {
            while (!inflater.finished() && totalRead < expectedSize) {
                val read = inflater.inflate(output, totalRead, expectedSize - totalRead)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                totalRead += read
            }
        } finally {
            inflater.end()
        }
        require(totalRead == expectedSize) {
            "Inflate sonrası beklenen boyuta ulaşılamadı: beklenen=$expectedSize, alınan=$totalRead"
        }
        return output
    }

    /**
     * TIFF Predictor=3 (floating point predictor) geri alma işlemi.
     *
     * Kaynak: Adobe Photoshop TIFF Technical Note 3 (chriscox.org/TIFFTN3d1.pdf).
     * Algoritma İKİ aşamalıdır ve Predictor=2'nin integer fark mantığından FARKLIDIR:
     *
     *  1) DecodeDeltaBytes: satırın TÜM byte'ları (width * bytesPerSample uzunluğunda,
     *     channels=1 olduğumuz için) üzerinde, byte seviyesinde kümülatif toplama
     *     (her byte, kendisinden önceki byte ile toplanır).
     *  2) Byte reorder: yukarıdaki adımdan çıkan "semi-BigEndian" düzenden, her örneğin
     *     gerçek (little-endian) byte sırasına geri dönülür. Encode sırasında byte'lar
     *     "byte-plane" gruplarına ayrılmıştı (tüm piksellerin en yüksek byte'ı bir blok,
     *     sonraki byte'lar başka blok...); burada bu gruplama geri açılır.
     *
     * Bu fonksiyon, strip içindeki HER SATIRI bağımsız olarak işler (TIFF spec: predictor
     * satır sınırlarını aşmaz).
     */
    private fun applyFloatingPointPredictorReversal(data: ByteArray, width: Int, bytesPerSample: Int, rowCount: Int) {
        val rowByteLen = width * bytesPerSample
        val rowBuffer = ByteArray(rowByteLen)

        for (row in 0 until rowCount) {
            val rowStart = row * rowByteLen
            System.arraycopy(data, rowStart, rowBuffer, 0, rowByteLen)

            // 1) DecodeDeltaBytes - satırın tüm byte'ları üzerinde kümülatif toplama
            for (col in 1 until rowByteLen) {
                rowBuffer[col] = ((rowBuffer[col].toInt() and 0xFF) + (rowBuffer[col - 1].toInt() and 0xFF)).toByte()
            }

            // 2) Byte reorder - semi-BigEndian gruplamadan native little-endian sıraya dön.
            // Referans (C, BigEndian=0 dalı): output[bytes*COL + BYTE] = input[(bytesPerSample-BYTE-1)*rowIncrement + COL]
            // rowIncrement = width (channels=1 olduğu için cols*channels = width)
            for (col in 0 until width) {
                for (b in 0 until bytesPerSample) {
                    val srcIndex = (bytesPerSample - b - 1) * width + col
                    val dstIndex = bytesPerSample * col + b
                    data[rowStart + dstIndex] = rowBuffer[srcIndex]
                }
            }
        }
    }

    /**
     * TIFF Predictor=2 (integer horizontal differencing) geri alma işlemi - sadece
     * tam sayı örnek formatları (UINT8/UINT16) için. Floating point veri için
     * Predictor=3 (applyFloatingPointPredictorReversal) kullanılır, BU FONKSİYON DEĞİL -
     * ikisi farklı algoritmalardır.
     */
    private fun applyHorizontalPredictorReversal(data: ByteArray, width: Int, bytesPerSample: Int, order: ByteOrder) {
        val buffer = ByteBuffer.wrap(data).order(order)
        val rowBytes = width * bytesPerSample
        val rowCount = data.size / rowBytes

        for (row in 0 until rowCount) {
            val rowStart = row * rowBytes
            when (bytesPerSample) {
                4 -> { // INT32/FLOAT32 - predictor floating point değil, integer fark mantığıyla çalışır (TIFF spec: tip 3 predictor floating point için ayrı, burada tip 2 integer fark)
                    var previous = buffer.getInt(rowStart)
                    for (col in 1 until width) {
                        val pos = rowStart + col * 4
                        val current = buffer.getInt(pos)
                        val restored = current + previous
                        buffer.putInt(pos, restored)
                        previous = restored
                    }
                }
                2 -> {
                    var previous = buffer.getShort(rowStart).toInt() and 0xFFFF
                    for (col in 1 until width) {
                        val pos = rowStart + col * 2
                        val current = buffer.getShort(pos).toInt() and 0xFFFF
                        val restored = (current + previous) and 0xFFFF
                        buffer.putShort(pos, restored.toShort())
                        previous = restored
                    }
                }
                1 -> {
                    var previous = data[rowStart].toInt() and 0xFF
                    for (col in 1 until width) {
                        val pos = rowStart + col
                        val current = data[pos].toInt() and 0xFF
                        val restored = (current + previous) and 0xFF
                        data[pos] = restored.toByte()
                        previous = restored
                    }
                }
            }
        }
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
