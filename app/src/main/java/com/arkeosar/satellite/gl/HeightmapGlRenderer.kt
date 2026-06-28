package com.arkeosar.satellite.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arkeosar.satellite.model.HeightmapGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * HeightmapGrid'i basit bir 3D yüzey (üçgen mesh) olarak render eden OpenGL ES 2.0 renderer.
 *
 * Tasarım kararı: Senin Kayser AreaScan / ArkeoSAR Ground Scan projelerindeki yaklaşımla
 * tutarlı olması için harici bir 3D kütüphane (örn. SceneForm, Filament) kullanılmadı -
 * doğrudan GLSurfaceView + el yazımı vertex/fragment shader'lar ile basit ve bağımlılıksız
 * bir render yapılıyor.
 *
 * Görselleştirme mantığı:
 *  - X/Z eksenleri grid'in genişlik/derinliğini temsil eder (düzleme yayılmış grid).
 *  - Y ekseni (yükseklik) skor değerinden gelir: yüksek anomali skoru = yüksek tepe.
 *  - Renk de skordan gelir (mavi->sarı->kırmızı), ResultActivity'deki 2D harita renklendirmesiyle
 *    tutarlı bir skala kullanılarak - kullanıcı iki görünüm arasında geçiş yaptığında aynı
 *    renk dilini görsün diye.
 */
class HeightmapGlRenderer(private val heightmap: HeightmapGrid) : GLSurfaceView.Renderer {

    companion object {
        private const val HEIGHT_SCALE = 1.4f // skor[0,1] -> dünya birimi yükseklik çarpanı

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

    @Volatile var rotationDegrees = 0f // kullanıcı dokunarak döndürebilir (ResultActivity bağlar)
    @Volatile var tiltDegrees = 35f

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.07f, 0.09f, 1f) // bg_base ile tutarlı koyu arka plan
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
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
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val eyeDistance = 4.2f
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val tiltRadians = Math.toRadians(tiltDegrees.toDouble())
        val eyeX = (eyeDistance * Math.cos(tiltRadians) * Math.sin(radians)).toFloat()
        val eyeZ = (eyeDistance * Math.cos(tiltRadians) * Math.cos(radians)).toFloat()
        val eyeY = (eyeDistance * Math.sin(tiltRadians)).toFloat()

        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)
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

    /** Grid'i (width x height skor matrisi) bir üçgen mesh'e çevirir - her hücre 2 üçgen (quad). */
    private fun buildMesh() {
        val w = heightmap.width
        val h = heightmap.height
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()

        fun heightAt(row: Int, col: Int): Float = heightmap.scores[row * w + col] * HEIGHT_SCALE
        fun worldX(col: Int): Float = (col.toFloat() / (w - 1) - 0.5f) * 2f
        fun worldZ(row: Int): Float = (row.toFloat() / (h - 1) - 0.5f) * 2f

        fun addVertex(row: Int, col: Int) {
            val score = heightmap.scores[row * w + col]
            positions.add(worldX(col)); positions.add(heightAt(row, col)); positions.add(worldZ(row))
            val (r, g, b) = scoreToRgb(score.toDouble())
            colors.add(r); colors.add(g); colors.add(b); colors.add(1f)
        }

        for (row in 0 until h - 1) {
            for (col in 0 until w - 1) {
                // Quad (row,col)-(row,col+1)-(row+1,col)-(row+1,col+1) -> 2 üçgen
                addVertex(row, col); addVertex(row, col + 1); addVertex(row + 1, col)
                addVertex(row, col + 1); addVertex(row + 1, col + 1); addVertex(row + 1, col)
            }
        }

        vertexCount = positions.size / 3

        vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(positions.toFloatArray()); position(0)
            }
        colorBuffer = ByteBuffer.allocateDirect(colors.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(colors.toFloatArray()); position(0)
            }
    }

    /** ResultActivity'deki 2D harita renklendirmesiyle (mavi->sarı->kırmızı) tutarlı skala. */
    private fun scoreToRgb(score: Double): Triple<Float, Float, Float> {
        val s = score.coerceIn(0.0, 1.0)
        return if (s < 0.5) {
            val t = (s / 0.5).toFloat()
            Triple(0.13f + t * (1f - 0.13f), 0.40f + t * (0.86f - 0.40f), 0.67f + t * (0.24f - 0.67f))
        } else {
            val t = ((s - 0.5) / 0.5).toFloat()
            Triple(1f, 0.86f - t * (0.86f - 0.12f), 0.24f - t * 0.24f)
        }
    }

    private fun loadShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, source); GLES20.glCompileShader(it) }
}
