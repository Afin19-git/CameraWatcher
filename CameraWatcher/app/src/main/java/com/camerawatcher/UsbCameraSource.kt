package com.camerawatcher

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.usb.USBMonitor

/**
 * USB-веб-камера через библиотеку AUSBC (ernestp/AndroidUSBCamera, 3.6.0).
 *
 * Схема взята из её собственной демонстрации (MultiCameraFragment):
 *   MultiCameraClient.register() -> onAttachDev -> requestPermission -> onConnectDev(ctrlBlock)
 *   -> CameraUVC.setUsbControlBlock -> openCamera -> кадры в IPreviewDataCallBack -> closeCamera при отключении.
 *
 * Кадры берём «сырыми» (CameraRequest.setRawPreviewData(true)): камера отдаёт готовый NV21 прямо из нативного
 * слоя, без показа на экран. Дальше NV21 превращается в картинку в нашем GL (см. GlRenderer.uploadNv21),
 * поэтому детектор движения, запись, надпись с датой и превью работают так же, как у камеры телефона.
 * Библиотека при этом сама крутит свой внутренний GL без окна (офскрин) — это штатный режим по её README.
 *
 * Все вызовы библиотеки делаются из главного потока, как в её демо. Кадры приходят из потока библиотеки.
 */
class UsbCameraSource(context: Context, private val listener: Listener) {

    interface Listener {
        /** Новый кадр NV21 (w*h байт яркости + w*h/2 байт V,U). Вызывается из потока библиотеки. */
        fun onFrame(data: ByteArray, w: Int, h: Int, tsNs: Long)
        /** Камера открылась и стримит (true) или пропала (false). Из главного потока. */
        fun onStatus(connected: Boolean)
        /** Понятная пользователю проблема (нет разрешения и т.п.). Из главного потока. */
        fun onProblem(msg: String)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var client: MultiCameraClient? = null
    private var camera: CameraUVC? = null
    private var device: UsbDevice? = null

    private var started = false
    private var stopped = false
    private var opened = false
    @Volatile private var lastFrameMs = 0L
    private var openedAtMs = 0L
    private var useYuyv = false
    private var retryScheduled = false
    private var deniedReported = false

    // ------------------------------------------------------------ жизненный цикл
    /** Запуск. Безопасно вызывать из любого потока. */
    fun start() {
        main.post { startOnMain() }
    }

    private fun startOnMain() {
        if (started || stopped) return
        started = true
        // Предыдущий экземпляр закрывает камеру асинхронно; новый открывать сразу рискованно (ошибка -99).
        val wait = (MIN_GAP_AFTER_STOP_MS - (SystemClock.elapsedRealtime() - lastStopMs)).coerceAtLeast(0L)
        main.postDelayed({ register() }, wait)
    }

    private fun register() {
        if (stopped) return
        try {
            val c = MultiCameraClient(app, deviceCallback)
            c.openDebug(true) // подробный лог библиотеки в logcat (теги AUSBC, UVCCamera, USBMonitor)
            client = c
            c.register()
            main.postDelayed(watchdog, WATCHDOG_MS)
            logI("USB: monitor registered")
        } catch (e: Exception) {
            logE("USB register", e)
            listener.onProblem(Loc.ctx(app).getString(R.string.w_usb_start_failed, e.message ?: ""))
        }
    }

    /** Остановка и освобождение. Не блокирует (можно вызывать с GL-потока). */
    fun stop() {
        stopped = true
        main.post {
            main.removeCallbacksAndMessages(null)
            try { camera?.setUsbControlBlock(null) } catch (_: Exception) {}
            closeCamera()
            try { client?.unRegister() } catch (e: Exception) { logE("USB unRegister", e) }
            try { client?.destroy() } catch (e: Exception) { logE("USB destroy", e) }
            client = null
            device = null
            lastStopMs = SystemClock.elapsedRealtime()
        }
    }

    // ------------------------------------------------------------ события USB
    private val deviceCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            device ?: return
            if (stopped) return
            logI("USB: attach ${device.deviceName} ${device.vendorId}:${device.productId}")
            if (this@UsbCameraSource.device != null && this@UsbCameraSource.device?.deviceId != device.deviceId) {
                return // работаем с одной камерой
            }
            this@UsbCameraSource.device = device
            if (camera == null) requestAccess(device)
        }

        override fun onDetachDec(device: UsbDevice?) {
            if (device?.deviceId != this@UsbCameraSource.device?.deviceId) return
            logI("USB: detach")
            try { camera?.setUsbControlBlock(null) } catch (_: Exception) {}
            closeCamera()
            this@UsbCameraSource.device = null
            deniedReported = false
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            ctrlBlock ?: return
            if (stopped) return
            if (device.deviceId != this@UsbCameraSource.device?.deviceId) return
            logI("USB: connected, opening camera")
            deniedReported = false
            openCamera(device, ctrlBlock)
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (device?.deviceId != this@UsbCameraSource.device?.deviceId) return
            logI("USB: disconnected")
            closeCamera()
        }

        override fun onCancelDev(device: UsbDevice?) {
            if (device?.deviceId != this@UsbCameraSource.device?.deviceId) return
            logI("USB: permission cancelled")
            closeCamera()
            if (!deniedReported) {
                deniedReported = true
                listener.onProblem(Loc.ctx(app).getString(R.string.usb_permission_denied))
            }
        }
    }

    /** Разрешение системы на доступ к камере (если уже выдано — диалога нет, сразу onConnectDev). */
    private fun requestAccess(d: UsbDevice) {
        try {
            client?.requestPermission(d)
        } catch (e: Exception) {
            logE("USB requestPermission", e)
        }
    }

    // ------------------------------------------------------------ камера
    private fun openCamera(d: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        closeCamera()
        try {
            val request = CameraRequest.Builder()
                .setPreviewWidth(Prefs.usbResW)
                .setPreviewHeight(Prefs.usbResH)
                .setRenderMode(CameraRequest.RenderMode.OPENGL) // без окна: офскрин-режим библиотеки
                .setRawPreviewData(true)                       // сырые NV21 прямо из камеры
                .setCaptureRawImage(false)
                .setAspectRatioShow(false)
                .setAudioSource(CameraRequest.AudioSource.NONE) // звук пишем сами, микрофоном телефона
                .setPreviewFormat(
                    if (useYuyv) CameraRequest.PreviewFormat.FORMAT_YUYV else CameraRequest.PreviewFormat.FORMAT_MJPEG
                )
                .create()
            val cam = CameraUVC(app, d)
            cam.setUsbControlBlock(ctrlBlock)
            cam.setCameraStateCallBack(stateCallback)
            cam.addPreviewDataCallBack(frameCallback)
            camera = cam
            opened = false
            lastFrameMs = 0L
            openedAtMs = SystemClock.elapsedRealtime()
            cam.openCamera<Any>(null, request)
            logI("USB: openCamera ${Prefs.usbResW}x${Prefs.usbResH} format=${if (useYuyv) "YUYV" else "MJPEG"}")
        } catch (e: Exception) {
            logE("USB openCamera", e)
            closeCamera()
            scheduleRetry()
        }
    }

    private fun closeCamera() {
        val cam = camera ?: return
        camera = null
        val was = opened
        opened = false
        try { cam.removePreviewDataCallBack(frameCallback) } catch (_: Exception) {}
        try { cam.closeCamera() } catch (e: Exception) { logE("USB closeCamera", e) }
        if (was) listener.onStatus(false)
    }

    private val stateCallback = object : ICameraStateCallBack {
        override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
            if (stopped || self !== camera) return
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    logI("USB: camera OPENED")
                    opened = true
                    openedAtMs = SystemClock.elapsedRealtime()
                    listener.onStatus(true)
                }
                ICameraStateCallBack.State.CLOSED -> logI("USB: camera CLOSED")
                ICameraStateCallBack.State.ERROR -> {
                    logE("USB: camera ERROR: $msg")
                    closeCamera()
                    scheduleRetry()
                }
            }
        }
    }

    private val frameCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            if (data == null || format != IPreviewDataCallBack.DataFormat.NV21) return
            if (width <= 0 || height <= 0 || data.size < width * height * 3 / 2) return
            lastFrameMs = SystemClock.elapsedRealtime()
            listener.onFrame(data, width and 1.inv(), height and 1.inv(), SystemClock.elapsedRealtimeNanos())
        }
    }

    /** Заново запросить доступ (на Android 9+ библиотека при этом пересоздаёт управляющий блок устройства). */
    private fun scheduleRetry() {
        if (stopped || retryScheduled) return
        retryScheduled = true
        main.postDelayed({
            retryScheduled = false
            val d = device
            if (!stopped && d != null && camera == null) requestAccess(d)
        }, RETRY_MS)
    }

    /**
     * Сторож: если камера «открыта», но кадры не идут, переоткрываем, переключая формат MJPEG <-> YUYV
     * (часть камер не отдаёт нормальный MJPEG). Если кадры шли и вдруг пропали — просто переоткрываем.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (camera != null && opened) {
                    val last = if (lastFrameMs != 0L) lastFrameMs else openedAtMs
                    if (now - last > NO_FRAMES_MS) {
                        val hadFrames = lastFrameMs != 0L
                        if (!hadFrames) useYuyv = !useYuyv
                        logE("USB: no frames for ${now - last} ms, reopening (yuyv=$useYuyv, hadFrames=$hadFrames)")
                        try { camera?.setUsbControlBlock(null) } catch (_: Exception) {}
                        closeCamera()
                        scheduleRetry()
                    }
                } else if (camera == null && device != null && !retryScheduled) {
                    // камера есть, но так и не открылась (например, пропустили событие) — просим снова
                    if (now - openedAtMs > NO_FRAMES_MS * 2) {
                        openedAtMs = now
                        device?.let { requestAccess(it) }
                    }
                }
            } catch (e: Exception) {
                logE("USB watchdog", e)
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    companion object {
        private const val WATCHDOG_MS = 2000L
        private const val NO_FRAMES_MS = 6000L
        private const val RETRY_MS = 3000L
        private const val MIN_GAP_AFTER_STOP_MS = 2500L
        @Volatile private var lastStopMs = 0L
    }
}
