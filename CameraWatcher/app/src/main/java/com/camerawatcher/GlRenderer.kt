package com.camerawatcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Один EGL-контекст на всё: превью, кодировщик и маленький буфер для детектора. */
class EglCore {
    private var display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw RuntimeException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        config = configs[0] ?: throw RuntimeException("EGL config not found")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
    }

    fun windowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        return s
    }

    fun pbuffer(w: Int, h: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0
        )

    fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw RuntimeException("eglMakeCurrent failed")
        EGL14.eglSwapInterval(display, 0)
    }

    fun swap(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)

    fun setPts(s: EGLSurface, ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, ns)
    }

    fun query(s: EGLSurface, what: Int): Int {
        val r = IntArray(1)
        EGL14.eglQuerySurface(display, s, what, r, 0)
        return r[0]
    }

    fun destroy(s: EGLSurface) {
        if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        context = EGL14.EGL_NO_CONTEXT
    }
}

class GlRenderer {
    var camTex = 0
        private set
    var fboW = 0
        private set
    var fboH = 0
        private set

    private class Prog(vs: String, fs: String) {
        val id: Int = link(vs, fs)
        fun a(n: String) = GLES20.glGetAttribLocation(id, n)
        fun u(n: String) = GLES20.glGetUniformLocation(id, n)

        companion object {
            private fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val st = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
                if (st[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(s)
                    GLES20.glDeleteShader(s)
                    throw RuntimeException("Shader error: $log")
                }
                return s
            }

            fun link(vs: String, fs: String): Int {
                val p = GLES20.glCreateProgram()
                GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(p)
                val st = IntArray(1)
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
                if (st[0] == 0) throw RuntimeException("Program link error: " + GLES20.glGetProgramInfoLog(p))
                return p
            }
        }
    }

    private val vsCam = """
        uniform mat4 uMVP; uniform mat4 uTex;
        attribute vec4 aPos; attribute vec4 aTex;
        varying vec2 vTex;
        void main() { gl_Position = uMVP * aPos; vTex = (uTex * aTex).xy; }
    """.trimIndent()

    private val fsCam = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex; uniform samplerExternalOES sTex;
        void main() { gl_FragColor = texture2D(sTex, vTex); }
    """.trimIndent()

    // 4 выборки вокруг точки => грубое усреднение при сильном уменьшении кадра
    private val fsLuma = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex; uniform samplerExternalOES sTex; uniform float uOff;
        void main() {
            vec4 c = texture2D(sTex, vTex + vec2(-uOff, -uOff))
                   + texture2D(sTex, vTex + vec2( uOff, -uOff))
                   + texture2D(sTex, vTex + vec2(-uOff,  uOff))
                   + texture2D(sTex, vTex + vec2( uOff,  uOff));
            c *= 0.25;
            float y = dot(c.rgb, vec3(0.299, 0.587, 0.114));
            gl_FragColor = vec4(y, y, y, 1.0);
        }
    """.trimIndent()

    private val vs2d = """
        attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;
        void main() { gl_Position = aPos; vTex = aTex; }
    """.trimIndent()

    private val fs2d = """
        precision mediump float;
        varying vec2 vTex; uniform sampler2D sTex;
        void main() { gl_FragColor = texture2D(sTex, vTex); }
    """.trimIndent()

    private lateinit var camProg: Prog
    private lateinit var lumaProg: Prog
    private lateinit var texProg: Prog

    private fun fb(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    private val quadPos = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val ovPos = fb(FloatArray(8))
    private val ovTex = fb(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    private var fbo = 0
    private var fboTex = 0
    private var ovTexId = 0
    private var ovW = 1
    private var ovH = 1
    private var ovText = ""

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 44f
    }

    fun init() {
        camProg = Prog(vsCam, fsCam)
        lumaProg = Prog(vsCam, fsLuma)
        texProg = Prog(vs2d, fs2d)

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        ovTexId = t[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Маленький кадр, в который рисуется картинка для детектора. */
    fun initFbo(w: Int, h: Int) {
        fboW = w
        fboH = h
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("FBO incomplete: $st")
    }

    /** Удаляет текущий буфер детектора перед пересозданием под новый размер (телефон повернули). */
    fun releaseFbo() {
        if (fbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (fboTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0); fboTex = 0 }
    }

    private fun drawQuad(aPos: Int, aTex: Int, pos: FloatBuffer, tex: FloatBuffer) {
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun drawCamWith(p: Prog, stm: FloatArray, mvp: FloatArray, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(p.u("sTex"), 0)
        GLES20.glUniformMatrix4fv(p.u("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(p.u("uTex"), 1, false, stm, 0)
    }

    /** Кадр камеры в текущую поверхность. */
    fun drawCamera(stm: FloatArray, mvp: FloatArray, w: Int, h: Int) {
        drawCamWith(camProg, stm, mvp, w, h)
        drawQuad(camProg.a("aPos"), camProg.a("aTex"), quadPos, quadTex)
    }

    /** Кадр камеры в виде яркости в маленький буфер детектора. */
    fun drawLuma(stm: FloatArray, mvp: FloatArray, off: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        drawCamWith(lumaProg, stm, mvp, fboW, fboH)
        GLES20.glUniform1f(lumaProg.u("uOff"), off)
        drawQuad(lumaProg.a("aPos"), lumaProg.a("aTex"), quadPos, quadTex)
    }

    fun readLuma(dst: ByteBuffer) {
        dst.clear()
        GLES20.glReadPixels(0, 0, fboW, fboH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dst)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Обновляет надпись (белые буквы на чёрном фоне). Дешёвая проверка, если текст не изменился. */
    fun setOverlayText(text: String) {
        if (text == ovText) return
        ovText = text
        val pad = 14
        val w = textPaint.measureText(text).toInt() + pad * 2
        val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val fm = textPaint.fontMetrics
        c.drawText(text, pad.toFloat(), h / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        ovW = w
        ovH = h
        bmp.recycle()
    }

    /** Надпись в левом нижнем углу; высота ~5,5% меньшей стороны кадра. */
    fun drawOverlay(vpW: Int, vpH: Int) {
        var targetH = max(24f, min(vpW, vpH) * 0.055f)
        var scale = targetH / ovH
        if (ovW * scale > vpW * 0.95f) {
            scale = vpW * 0.95f / ovW
            targetH = ovH * scale
        }
        val wPx = ovW * scale
        val m = vpH * 0.02f
        val x0 = -1f + 2f * m / vpW
        val x1 = x0 + 2f * wPx / vpW
        val y0 = -1f + 2f * m / vpH
        val y1 = y0 + 2f * targetH / vpH
        ovPos.position(0)
        ovPos.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y1))
        ovPos.position(0)
        GLES20.glViewport(0, 0, vpW, vpH)
        GLES20.glUseProgram(texProg.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTexId)
        GLES20.glUniform1i(texProg.u("sTex"), 0)
        drawQuad(texProg.a("aPos"), texProg.a("aTex"), ovPos, ovTex)
    }

    fun release() {
        try {
            GLES20.glDeleteTextures(3, intArrayOf(camTex, ovTexId, fboTex), 0)
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        } catch (e: Exception) { /* контекст уже мог быть потерян */ }
    }
}
