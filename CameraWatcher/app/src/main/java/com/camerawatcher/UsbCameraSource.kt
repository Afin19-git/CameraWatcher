package com.camerawatcher

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor

/**
 * USB-веб-камера (UVC) по OTG вместо своей камеры телефона — через библиотеку AndroidUSBCamera
 * (https://github.com/WojciechCzeronko/AndroidUSBCamera, форк saki4510t/UVCCamera).
 *
 * Библиотека сама умеет рисовать декодированные кадры прямо в переданный ей Surface — тот же Surface,
 * в который иначе пишет Camera2 (обёрнутый вокруг camTex). Поэтому весь остальной конвейер (GL, детектор
 * движения, кодирование, надпись с датой) не меняется: для него неважно, кто поставляет кадры.
 *
 * Важно про разрешение на доступ к USB-устройству: это системный диалог Android, а не runtime-permission
 * приложения, и он должен быть показан поверх активного Activity. Если веб-камера подключается первый раз,
 * когда приложение работает в фоне как служба (без видимого экрана), диалог может не появиться. Поэтому
 * первое подключение камеры нужно делать в превью (экран приложения открыт) — тогда Android запомнит
 * разрешение для этого устройства, и дальнейшие фоновые запуски пройдут без диалога.
 *
 * Жизненный цикл: одна активная сессия CameraUVC за раз. Перед каждым новым открытием предыдущая сессия
 * закрывается (closeCamera → destroy освобождает USB-интерфейс). Иначе повторное открытие получает
 * «open failed: result=-1/-99», потому что камера уже занята нашей же старой сессией.
 */
class UsbCameraSource(private val ctx: Context) {

    interface Listener {
        /** Камера открыта и пишет кадры в наш Surface; camW/camH — реально согласованный размер. */
        fun onReady(camW: Int, camH: Int)
        fun onError(message: String)
        /** Камеру отключили; при повторном подключении приёмник USB откроет её снова сам. */
        fun onDisconnected()
    }

    private var client: MultiCameraClient? = null
    private var camera: MultiCameraClient.ICamera? = null
    private var activeDeviceId: Int? = null
    @Volatile private var running = false
    @Volatile var connected = false
        private set

    private class SurfaceAspect(private val s: Surface, private val w: Int, private val h: Int) : IAspectRatio {
        override fun setAspectRatio(width: Int, height: Int) {}
        override fun getSurfaceWidth() = w
        override fun getSurfaceHeight() = h
        override fun getSurface(): Surface = s
        override fun postUITask(task: () -> Unit) { task() }
    }

    /** Закрывает текущую сессию камеры и освобождает USB-интерфейс. Безопасно вызывать многократно. */
    private fun releaseCamera() {
        val cam = camera ?: return
        camera = null
        activeDeviceId = null
        connected = false
        try { cam.closeCamera() } catch (_: Exception) {}
    }

    /** Ищет UVC-камеру и направляет её кадры в [surface]. [reqW]/[reqH] — желаемый размер. */
    fun start(surface: Surface, reqW: Int, reqH: Int, listener: Listener) {
        stop()
        running = true
        val appCtx = ctx.applicationContext

        fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?, w: Int, h: Int, fallbackLeft: Boolean) {
            if (!running) return
            releaseCamera()
            try {
                val cam = CameraUVC(appCtx, device)
                cam.setUsbControlBlock(ctrlBlock)
                cam.setCameraStateCallBack(object : ICameraStateCallBack {
                    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                        // события от уже закрытой сессии игнорируем
                        if (!running || camera !== self) return
                        when (code) {
                            ICameraStateCallBack.State.OPENED -> {
                                connected = true
                                val req = cam.getCameraRequest()
                                listener.onReady(req?.previewWidth ?: w, req?.previewHeight ?: h)
                            }
                            ICameraStateCallBack.State.ERROR -> {
                                if (msg?.contains("unsupported preview size") == true && fallbackLeft) {
                                    // камера не поддерживает запрошенный размер — пробуем запасной 640×480
                                    openCamera(device, ctrlBlock, 640, 480, false)
                                } else {
                                    releaseCamera()
                                    listener.onError(msg ?: "USB camera error")
                                }
                            }
                            ICameraStateCallBack.State.CLOSED -> connected = false
                        }
                    }
                })
                camera = cam
                activeDeviceId = device.deviceId
                val request = CameraRequest.Builder()
                    .setPreviewWidth(w)
                    .setPreviewHeight(h)
                    .setRenderMode(CameraRequest.RenderMode.OPENGL)
                    .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                    .setAudioSource(CameraRequest.AudioSource.NONE) // звук пишем отдельно с микрофона телефона
                    .setAspectRatioShow(false)
                    .create()
                cam.openCamera(SurfaceAspect(surface, w, h), request)
            } catch (e: Exception) {
                releaseCamera()
                if (running) listener.onError(e.message ?: "USB camera error")
            }
        }

        val c = MultiCameraClient(appCtx, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (!running) return
                client?.requestPermission(device)
            }
            override fun onDetachDec(device: UsbDevice?) {
                if (!running) return
                releaseCamera()
                listener.onDisconnected()
            }
            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                if (!running) return
                // то же устройство уже открыто (второе событие подключения) — не открываем повторно
                if (camera != null && activeDeviceId == device.deviceId) return
                openCamera(device, ctrlBlock, reqW, reqH, true)
            }
            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (!running) return
                releaseCamera()
                listener.onDisconnected()
            }
            override fun onCancelDev(device: UsbDevice?) {
                if (running) listener.onError(Loc.ctx(appCtx).getString(R.string.usb_permission_denied))
            }
        })
        client = c
        c.register()
        // камера уже могла быть подключена до запуска — запрашиваем доступ сами
        c.getDeviceList(null)?.firstOrNull()?.let { c.requestPermission(it) }
    }

    /** Полная остановка: закрыть камеру, снять приёмник USB, освободить клиент. */
    fun stop() {
        running = false
        releaseCamera()
        val c = client
        client = null
        try { c?.unRegister() } catch (_: Exception) {}
        try { c?.destroy() } catch (_: Exception) {}
        connected = false
    }
}
