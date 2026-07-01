package com.arkeosar.satellite.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arkeosar.satellite.filter.StructureProfile
import com.arkeosar.satellite.model.HeightmapGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * HeightmapGrid'i 3D yüzey (üçgen mesh) olarak render eden OpenGL ES 2.0 renderer.
 *
 * İki katmanlı render:
 *  1. ZEMIN KATMANI (DEM): rawDem varsa gerçek arazi formu, yoksa düz bir taban.
 *     Gri tonlarla renklendirilen zemin, arazi bağlamını gösterir.
 *  2. ANOMALİ KATMANI: Filtre skoru Y eksenine (yükseklik) dönüştürülerek zemin
 *     üstüne eklenir. Adaptif ölçekleme: skor aralığı küçükse yükseltilir —
 *     ince farklılıklar görünür kılınır.
 *
 * Yapı tipine göre renklendirme:
 *  - Oda/Mahzen/Lahit: turuncu-sarı (kompakt kapalı yapılar)
 *  - Kuyu: mavi-cyan (derin dikey yapılar)
 *  - Tünel/Koridor/Giriş: yeşil (uzayan doğrusal yapılar)
 *  - Mezar/Sarkofag: mor-pembe (gömme yapıları)
 *  - Diğer/Genel: mavi→sarı→kırmızı (standart ısı haritası)
 */
class HeightmapGlRenderer(
    initialHeightmap: HeightmapGrid,
    initialProfile: StructureProfile = StructureProfile.VOID
) : GLSurfaceView.Renderer {

    companion object {
        private const val DEM_SCALE = 0.3f       // DEM yüksekliği [m] -> dünya birimi (görsel oran)
        private const val ANOMALY_SCALE = 1.8f   // anomali skoru [0,1] -> dünya birimi
        private const val DEM_LAYER_OFFSET = 0f  // DEM zemin katmanının taban ofseti

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vColor = aColor;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
    }

    @Volatile private var heightmap = initialHeightmap
    @Volatile private var profile = initialProfile
    @Volatile private var meshDirty = true

    fun updateGrid(newHeightmap: HeightmapGrid) { heightmap = newHeightmap; meshDirty = true }
    fun updateProfile(newProfile: StructureProfile) { profile = newProfile; meshDirty = true }

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var colorBuffer: FloatBuffer
    private var vertexCount = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    @Volatile var rotationDegrees = 0f
    @Volatile var tiltDegrees = 35f

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.1f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(it, loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(it)
        }
        buildMesh()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.5f, 20f)
    }

    override fun onDrawFrame(unused: GL10?) {
        if (meshDirty) { buildMesh(); meshDirty = false }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val eyeDistance = 4.5f
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val tilt = Math.toRadians(tiltDegrees.toDouble())
        val eyeX = (eyeDistance * Math.cos(tilt) * Math.sin(rad)).toFloat()
        val eyeZ = (eyeDistance * Math.cos(tilt) * Math.cos(rad)).toFloat()
        val eyeY = (eyeDistance * Math.sin(tilt)).toFloat()

        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0.2f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(colorHandle)
        colorBuffer.position(0)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun buildMesh() {
        val w = heightmap.width
        val h = heightmap.height
        val scores = heightmap.scores
        val dem = heightmap.rawDem

        // Adaptif yükseklik ölçekleme: skor aralığı 0.3'ten azsa, görünürlük için büyüt.
        val scoreMin = scores.min()
        val scoreMax = scores.max()
        val scoreRange = (scoreMax - scoreMin).coerceAtLeast(0.01f)
        val adaptiveScale = if (scoreRange < 0.3f) ANOMALY_SCALE * (0.3f / scoreRange) else ANOMALY_SCALE
        val effectiveScale = adaptiveScale.coerceAtMost(4f) // çok aşırı büyütmeyi engelle

        // DEM normalizasyonu (varsa): görsel zemin için [0,DEM_SCALE] aralığına çek.
        val demMin = dem?.min() ?: 0f
        val demMax = dem?.max() ?: 0f
        val demRange = (demMax - demMin).coerceAtLeast(1f)

        fun demHeightAt(idx: Int): Float {
            if (dem == null) return 0f
            return ((dem[idx] - demMin) / demRange) * DEM_SCALE
        }

        fun anomalyHeightAt(idx: Int): Float =
            ((scores[idx] - scoreMin) / scoreRange) * effectiveScale

        fun worldX(col: Int): Float = (col.toFloat() / (w - 1) - 0.5f) * 2f
        fun worldZ(row: Int): Float = (row.toFloat() / (h - 1) - 0.5f) * 2f

        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()

        fun addVertex(row: Int, col: Int, isGroundLayer: Boolean) {
            val idx = row * w + col
            val demH = demHeightAt(idx)
            val anomalyH = if (isGroundLayer) 0f else anomalyHeightAt(idx)
            val totalY = demH + DEM_LAYER_OFFSET + anomalyH

            positions.add(worldX(col))
            positions.add(totalY)
            positions.add(worldZ(row))

            val (r, g, b, a) = if (isGroundLayer) {
                // Zemin katmanı: gri tonlar, arazi bağlamı
                val grayBase = 0.25f + (demH / DEM_SCALE) * 0.3f
                floatArrayOf(grayBase, grayBase + 0.05f, grayBase + 0.08f, 0.85f)
            } else {
                // Anomali katmanı: yapı tipine göre renk
                val score = (scores[idx] - scoreMin) / scoreRange
                val rgba = profileColor(score.toDouble(), profile)
                floatArrayOf(rgba[0], rgba[1], rgba[2], rgba[3])
            }
            colors.add(r); colors.add(g); colors.add(b); colors.add(a)
        }

        // Önce zemin katmanı (DEM varsa göster)
        if (dem != null) {
            for (row in 0 until h - 1) {
                for (col in 0 until w - 1) {
                    addVertex(row, col, true); addVertex(row, col + 1, true); addVertex(row + 1, col, true)
                    addVertex(row, col + 1, true); addVertex(row + 1, col + 1, true); addVertex(row + 1, col, true)
                }
            }
        }

        // Sonra anomali katmanı (filtre skoru)
        for (row in 0 until h - 1) {
            for (col in 0 until w - 1) {
                addVertex(row, col, false); addVertex(row, col + 1, false); addVertex(row + 1, col, false)
                addVertex(row, col + 1, false); addVertex(row + 1, col + 1, false); addVertex(row + 1, col, false)
            }
        }

        vertexCount = positions.size / 3
        vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(positions.toFloatArray()); position(0) }
        colorBuffer = ByteBuffer.allocateDirect(colors.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(colors.toFloatArray()); position(0) }
    }

    /**
     * Yapı tipine göre renk paleti:
     *  - Oda/Mahzen/Lahit: turuncu-altın (kapalı kompakt yapılar)
     *  - Kuyu: mavi-cyan (derin dikey)
     *  - Tünel/Koridor/Giriş: yeşil-sarı (uzayan doğrusal)
     *  - Mezar/Sarkofag: mor-pembe (gömme yapıları)
     *  - Diğer: klasik ısı haritası (mavi→sarı→kırmızı)
     */
    private fun profileColor(score: Double, p: StructureProfile): FloatArray {
        val s = score.coerceIn(0.0, 1.0).toFloat()
        return when (p) {
            StructureProfile.CHAMBER, StructureProfile.CRYPT, StructureProfile.SARCOPHAGUS -> {
                // Turuncu-altın: kompakt kapalı yapılar
                floatArrayOf(0.2f + s * 0.8f, 0.15f + s * 0.7f, 0f, 0.9f)
            }
            StructureProfile.WELL -> {
                // Mavi-cyan: derin dikey yapılar
                floatArrayOf(0f, 0.3f + s * 0.5f, 0.5f + s * 0.5f, 0.9f)
            }
            StructureProfile.TUNNEL, StructureProfile.CORRIDOR, StructureProfile.ENTRANCE -> {
                // Yeşil-sarı: uzayan doğrusal yapılar
                floatArrayOf(s * 0.7f, 0.4f + s * 0.6f, 0f, 0.9f)
            }
            StructureProfile.GRAVE, StructureProfile.SARCOPHAGUS -> {
                // Mor-pembe: gömme yapıları
                floatArrayOf(0.4f + s * 0.6f, 0f, 0.4f + s * 0.4f, 0.9f)
            }
            else -> {
                // Klasik ısı haritası: mavi→sarı→kırmızı
                if (s < 0.5f) {
                    val t = s / 0.5f
                    floatArrayOf(0.13f + t * 0.87f, 0.40f + t * 0.46f, 0.67f - t * 0.43f, 0.9f)
                } else {
                    val t = (s - 0.5f) / 0.5f
                    floatArrayOf(1f, 0.86f - t * 0.74f, 0.24f - t * 0.24f, 0.9f)
                }
            }
        }
    }

    private fun loadShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
}
