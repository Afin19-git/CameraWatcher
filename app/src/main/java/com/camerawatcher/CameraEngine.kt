package com.camerawatcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Вся работа с камерой в одном объекте:
 *   камера -> SurfaceTexture -> GL -> { превью, кодировщик (с надписью), маленький кадр для детектора }.
 *
 * armed = false: только превью и подсветка движения (для настройки).
 * armed = true : ещё и запись клипов по движению.
 *
 * Камера открывается один раз и отдаёт ровно один поток, поэтому это работает и на очень слабых устройствах.
 */
class CameraEngine(private val ctx: Context, val armed: Boolean, displayRotation: Int) {

    interface Listener {
        fun onAnalysis(cols: Int, rows: Int, active: BooleanArray, motion: Boolean, recording: Boolean) {}
        fun onWarning(msg: String) {}
        fun onClipSaved(file: File) {}
        /** Только для превью (armed=false): телефон повернули, картинка и сетка пересчитаны. */
        fun onGeometryChanged(g: Geometry) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /**
     * В режиме превью (armed=false) пересчитывается на лету, пока телефон крутят в руках, подбирая крепление —
     * так его удобно позиционировать до фактической установки. В боевом режиме (armed=true, сервис записи)
     * фиксируется один раз при старте: менять размер кадра посреди записи нельзя.
     */
    var geometry: Geometry = GeometryBuilder.build(ctx, displayRotation)
        private set

    @Volatile var recording = false
        private set
    @Volatile var stopped = false
        private set

    private val g: Geometry get() = geometry
    private val thread = HandlerThread("cw-gl")
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())

    private var egl: EglCore? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: GlRenderer? = null
    private var st: SurfaceTexture? = null
    private var camSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE

    private val detector = MotionDetector()
    private val stm = FloatArray(16)
    private val mvp = FloatArray(16)
    private var lumaOff = 0.004f
    private lateinit var lumaBuf: ByteBuffer
    private lateinit var lumaArr: ByteArray

    private var lastAnalysis = 0L
    private var lastMotion = 0L
    private var recStart = 0L
    private var recFailUntil = 0L
    private var recorder: VideoRecorder? = null
    private var encEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var firstTs = -1L
    private var tickCount = 0
    private var prevLowDrive = false
    private var prevLowDevice = false
    private var released = false
    private var mvpReady = false
    private var recErrors = 0

    companion object {
        private const val ANALYSIS_MS = 200L      // детектор работает 5 раз в секунду
        private const val MAX_CLIP_MS = 5 * 60_000L // длинные события режем на куски по 5 минут
    }

    // ------------------------------------------------------------ жизненный цикл
    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        if (!armed) DeviceOrientation.start(ctx) // превью: следим за поворотом непрерывно, пока камеру не выключат
        handler.post { safeInit() }
    }

    private fun safeInit() {
        try {
            initGl()
            openCamera()
            handler.postDelayed(tickRunnable, 1000)
        } catch (e: Exception) {
            logE("init failed", e)
            warn(str(R.string.w_camera_start_failed, e.message ?: ""))
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (!armed) DeviceOrientation.stop()
        val latch = CountDownLatch(1)
        handler.post {
            try { releaseAll() } catch (e: Exception) { logE("release", e) } finally { latch.countDown() }
        }
        latch.await(6, TimeUnit.SECONDS)
        thread.quitSafely()
        listeners.clear()
    }

    private fun releaseAll() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (recording) stopRecording()
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        val e = egl
        if (e != null) {
            if (previewEgl != EGL14.EGL_NO_SURFACE) e.destroy(previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
            try { st?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { st?.release() } catch (_: Exception) {}
            try { camSurface?.release() } catch (_: Exception) {}
            renderer?.release()
            e.destroy(pbuf)
            e.release()
        }
        egl = null
    }

    // ------------------------------------------------------------ превью и настройки
    /** Подключить/отключить поверхность превью. Ждёт, пока GL-поток отпустит старую (важно для surfaceDestroyed). */
    fun setPreviewSurface(surface: Surface?) {
        if (stopped) return
        val latch = CountDownLatch(1)
        val ok = handler.post {
            try {
                val e = egl
                if (e != null) {
                    if (previewEgl != EGL14.EGL_NO_SURFACE) {
                        e.makeCurrent(pbuf)
                        e.destroy(previewEgl)
                        previewEgl = EGL14.EGL_NO_SURFACE
                    }
                    if (surface != null && surface.isValid) previewEgl = e.windowSurface(surface)
                }
            } catch (ex: Exception) {
                logE("preview surface", ex)
                previewEgl = EGL14.EGL_NO_SURFACE
            } finally {
                latch.countDown()
            }
        }
        if (ok) latch.await(1, TimeUnit.SECONDS)
    }

    /** Перечитать чувствительность и маску из настроек (можно вызывать «на лету»). */
    fun reloadConfig() {
        if (stopped) return
        handler.post { applyDetectorConfig() }
    }

    private fun applyDetectorConfig() {
        detector.configure(g.cols, g.rows, Prefs.maskFor(g.cols, g.rows), Prefs.sensitivity)
    }

    /** Только для превью: телефон могли повернуть в руках, пока подбирают крепление. Дёшево — раз в секунду. */
    private fun checkOrientationChange() {
        val newG = try { GeometryBuilder.build(ctx, DeviceOrientation.current()) } catch (e: Exception) { return }
        if (newG.rot == geometry.rot) return // поворот не поменялся
        applyNewGeometry(newG)
    }

    /** Выполняется на GL-потоке (тик уже там). Пересчитывает буфер детектора и матрицу под новый поворот. */
    private fun applyNewGeometry(newG: Geometry) {
        val r = renderer ?: return
        val e = egl ?: return
        try {
            e.makeCurrent(pbuf)
            r.releaseFbo()
            r.initFbo(newG.cols * MotionDetector.CELL_PX, newG.rows * MotionDetector.CELL_PX)
            lumaBuf = ByteBuffer.allocateDirect(r.fboW * r.fboH * 4).order(ByteOrder.nativeOrder())
            lumaArr = ByteArray(r.fboW * r.fboH * 4)
            lumaOff = 0.3f / max(r.fboW, r.fboH)
            geometry = newG
            applyDetectorConfig()
            mvpReady = false // пересчитаем матрицу по новому повороту на следующем кадре
        } catch (ex: Exception) {
            logE("applyNewGeometry", ex)
            return
        }
        main.post { for (l in listeners) l.onGeometryChanged(newG) }
    }

    // ------------------------------------------------------------ GL и камера
    private fun initGl() {
        val e = EglCore()
        egl = e
        pbuf = e.pbuffer(1, 1)
        e.makeCurrent(pbuf)
        val r = GlRenderer()
        r.init()
        r.initFbo(g.cols * MotionDetector.CELL_PX, g.rows * MotionDetector.CELL_PX)
        renderer = r
        lumaBuf = ByteBuffer.allocateDirect(r.fboW * r.fboH * 4).order(ByteOrder.nativeOrder())
        lumaArr = ByteArray(r.fboW * r.fboH * 4)
        lumaOff = 0.3f / max(r.fboW, r.fboH)

        val tex = SurfaceTexture(r.camTex)
        tex.setDefaultBufferSize(g.camW, g.camH)
        tex.setOnFrameAvailableListener({ onFrame() }, handler)
        st = tex
        camSurface = Surface(tex)
        applyDetectorConfig()
    }

    /**
     * Собираем матрицу по первой реальной матрице SurfaceTexture. Многие телефоны (Camera2) уже поворачивают
     * картинку под сенсор внутри неё — тогда докручиваем только недостающее, иначе кадр окажется повёрнутым.
     */
    private fun buildMvpIfNeeded() {
        if (mvpReady) return
        // куда в буфере смотрит «верх» итогового кадра
        val dx = stm[4]
        val dy = stm[5]
        val included = if (Math.abs(dy) >= Math.abs(dx)) { if (dy < 0) 0 else 180 } else { if (dx < 0) 90 else 270 }
        val extra = (g.rot - included + 360) % 360
        val bw = if (included == 90 || included == 270) g.camH else g.camW
        val bh = if (included == 90 || included == 270) g.camW else g.camH
        logI("stm rotation=$included, total=${g.rot}, extra=$extra, out=${g.outW}x${g.outH}")
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, 2f / g.outW, 2f / g.outH, 1f)
        Matrix.rotateM(mvp, 0, -extra.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, bw / 2f, bh / 2f, 1f)
        mvpReady = true
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (released) return
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = CameraInfo.get(ctx).id
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (released) { c.close(); return }
                    camera = c
                    createSession()
                }
                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    camera = null
                    scheduleReopen()
                }
                override fun onError(c: CameraDevice, error: Int) {
                    logE("camera error $error")
                    c.close()
                    camera = null
                    scheduleReopen()
                }
            }, handler)
        } catch (e: Exception) {
            logE("openCamera", e)
            scheduleReopen()
        }
    }

    private fun scheduleReopen() {
        if (!released) handler.postDelayed({ openCamera() }, 2000)
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val c = camera ?: return
        val surf = camSurface ?: return
        try {
            c.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (released) { s.close(); return }
                    session = s
                    try {
                        val rb = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        rb.addTarget(surf)
                        rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, g.fpsRange)
                        rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        s.setRepeatingRequest(rb.build(), null, handler)
                    } catch (e: Exception) {
                        logE("repeating request", e)
                        scheduleReopen()
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    logE("session configure failed")
                    scheduleReopen()
                }
            }, handler)
        } catch (e: Exception) {
            logE("createSession", e)
            scheduleReopen()
        }
    }

    // ------------------------------------------------------------ кадры
    private fun onFrame() {
        if (released) return
        val e = egl ?: return
        val r = renderer ?: return
        val tex = st ?: return
        try {
            e.makeCurrent(pbuf)
            tex.updateTexImage()
            tex.getTransformMatrix(stm)
            buildMvpIfNeeded()
            val ts = tex.timestamp
            val now = SystemClock.elapsedRealtime()

            if (now - lastAnalysis >= ANALYSIS_MS) {
                lastAnalysis = now
                analyze(r, now)
            }

            val wallMs = System.currentTimeMillis()
            val text = (if (Prefs.label.isNotBlank()) Prefs.label.trim() + "  " else "") + Fmt.overlay(wallMs)

            val rec = recorder
            val es = encEgl
            if (rec != null && es != EGL14.EGL_NO_SURFACE) {
                if (firstTs < 0) {
                    firstTs = ts
                    rec.markFirstFrame()
                }
                e.makeCurrent(es)
                r.setOverlayText(text)
                r.drawCamera(stm, mvp, g.outW, g.outH)
                r.drawOverlay(g.outW, g.outH)
                e.setPts(es, ts - firstTs)
                e.swap(es)
                rec.drain()
                recErrors = 0
            }

            val ps = previewEgl
            if (ps != EGL14.EGL_NO_SURFACE) {
                e.makeCurrent(ps)
                r.setOverlayText(text)
                val pw = e.query(ps, EGL14.EGL_WIDTH)
                val ph = e.query(ps, EGL14.EGL_HEIGHT)
                if (pw > 0 && ph > 0) {
                    r.drawCamera(stm, mvp, pw, ph)
                    r.drawOverlay(pw, ph)
                    e.swap(ps)
                }
            }
        } catch (ex: Exception) {
            logE("onFrame", ex)
            if (recording && ++recErrors > 5) { // запись сломана (например, муксер не принял кодек) — не мучаем её дальше
                recErrors = 0
                try { stopRecording() } catch (_: Exception) {}
                recFailUntil = SystemClock.elapsedRealtime() + 30_000
                warn(str(R.string.w_record_aborted, ex.message ?: ""))
            }
        }
    }

    private fun analyze(r: GlRenderer, now: Long) {
        r.drawLuma(stm, mvp, lumaOff)
        r.readLuma(lumaBuf)
        lumaBuf.rewind()
        lumaBuf.get(lumaArr)
        val motion = detector.analyze(lumaArr, r.fboW, r.fboH)
        if (motion) {
            lastMotion = now
            if (armed && !recording && now >= recFailUntil && !storageBlocked()) startRecording(now)
        }
        val act = detector.active.copyOf()
        val rec = recording
        main.post { for (l in listeners) l.onAnalysis(g.cols, g.rows, act, motion, rec) }
    }

    private fun storageBlocked() = Prefs.lowDevice || Prefs.lowDrive

    // ------------------------------------------------------------ запись
    private fun startRecording(now: Long) {
        val e = egl ?: return
        val wall = System.currentTimeMillis()
        val dir = Storage.dayDir(ctx, wall)
        val file = File(dir, "alarm_${Fmt.stamp(wall)}.mp4")
        val bitrate = Prefs.bitrateKbps * 1000
        var mime = Codecs.chooseMime()
        var rec: VideoRecorder? = null
        try {
            rec = VideoRecorder(file, mime, g.outW, g.outH, g.fps, bitrate, !Prefs.mute)
            try {
                rec.start()
            } catch (ex: Exception) {
                rec.abort()
                if (mime != Codecs.HEVC) throw ex
                // HEVC не завёлся — навсегда переходим на H.264
                logE("HEVC failed, fallback to H.264", ex)
                Prefs.hevcFailed = true
                warn(str(R.string.w_hevc_fallback))
                mime = Codecs.AVC
                rec = VideoRecorder(file, mime, g.outW, g.outH, g.fps, bitrate, !Prefs.mute)
                rec.start()
            }
            encEgl = e.windowSurface(rec.inputSurface)
            recorder = rec
            firstTs = -1
            recStart = now
            recording = true
        } catch (ex: Exception) {
            logE("startRecording", ex)
            try { rec?.abort() } catch (_: Exception) {}
            recorder = null
            encEgl = EGL14.EGL_NO_SURFACE
            recFailUntil = now + 30_000
            warn(str(R.string.w_record_start_failed, ex.message ?: ""))
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        val e = egl
        recording = false
        recorder = null
        try {
            if (e != null) e.makeCurrent(pbuf)
            val ok = rec.finish()
            if (ok) main.post { for (l in listeners) l.onClipSaved(rec.finalFile) }
        } catch (ex: Exception) {
            logE("stopRecording", ex)
        }
        if (e != null && encEgl != EGL14.EGL_NO_SURFACE) e.destroy(encEgl)
        encEgl = EGL14.EGL_NO_SURFACE
    }

    // ------------------------------------------------------------ раз в секунду
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (released) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (recording) {
                    if (now - lastMotion > Prefs.postRollSec * 1000L || storageBlocked()) {
                        stopRecording()
                    } else if (now - recStart > MAX_CLIP_MS) {
                        stopRecording()
                        startRecording(now) // событие продолжается — новый файл
                    }
                }
                if (armed) {
                    tickCount++
                    if (tickCount % 10 == 1) checkStorage()
                    val d = Prefs.lowDrive
                    if (d && !prevLowDrive) warn(str(R.string.w_drive_full))
                    prevLowDrive = d
                } else {
                    checkOrientationChange()
                }
            } catch (ex: Exception) {
                logE("tick", ex)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkStorage() {
        val free = Storage.freeBytes(ctx)
        if (free < 300L * 1024 * 1024) Prefs.lowDevice = true
        else if (free > 500L * 1024 * 1024) Prefs.lowDevice = false
        val d = Prefs.lowDevice
        if (d && !prevLowDevice) warn(str(R.string.w_low_space))
        prevLowDevice = d
    }

    private fun str(id: Int, vararg a: Any): String = Loc.ctx(ctx).getString(id, *a)

    private fun warn(msg: String) {
        main.post { for (l in listeners) l.onWarning(msg) }
    }
}
