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
 * движения, кодирование, надпись с датой) не меняется ни на строчку: для него неважно, кто поставляет кадры.
 *
 * Важно про разрешение на доступ к USB-устройству: это системный диалог Android, а не runtime-permission
 * приложения, и он должен быть показан поверх активного Activity. Если веб-камера подключается первый раз,
 * когда приложение работает в фоне как служба (без видимого экрана), диалог может не появиться. Поэтому
 * первое подключение камеры нужно делать в превью (экран приложения открыт) — тогда Android запомнит
 * разрешение для этого устройства навсегда, и дальнейшие фоновые запуски пройдут без диалога.
 */
class UsbCameraSource(private val ctx: Context) {

    interface Listener {
        /** Камера открыта и пишет кадры в наш Surface; camW/camH — реально согласованный размер. */
        fun onReady(camW: Int, camH: Int)
        fun onError(message: String)
        /** Камеру отключили или она перестала отвечать; попытки переподключения продолжаются сами. */
        fun onDisconnected()
    }

    private var client: MultiCameraClient? = null
    private var camera: MultiCameraClient.ICamera? = null
    @Volatile var connected = false
        private set

    private class SurfaceAspect(private val s: Surface, private val w: Int, private val h: Int) : IAspectRatio {
        override fun setAspectRatio(width: Int, height: Int) {}
        override fun getSurfaceWidth() = w
        override fun getSurfaceHeight() = h
        override fun getSurface(): Surface = s
        override fun postUITask(task: () -> Unit) { task() }
    }

    /** Ищет первую подключённую UVC-камеру и направляет её кадры в [surface]. [reqW]/[reqH] — желаемый размер. */
    fun start(surface: Surface, reqW: Int, reqH: Int, listener: Listener) {
        val appCtx = ctx.applicationContext

        fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?) {
            try {
                val cam = CameraUVC(appCtx, device)
                cam.setUsbControlBlock(ctrlBlock)
                cam.setCameraStateCallBack(object : ICameraStateCallBack {
                    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                        when (code) {
                            ICameraStateCallBack.State.OPENED -> {
                                connected = true
                                val req = cam.getCameraRequest()
                                listener.onReady(req?.previewWidth ?: reqW, req?.previewHeight ?: reqH)
                            }
                            ICameraStateCallBack.State.ERROR -> {
                                connected = false
                                listener.onError(msg ?: "USB camera error")
                            }
                            ICameraStateCallBack.State.CLOSED -> {
                                connected = false
                            }
                        }
                    }
                })
                camera = cam
                val request = CameraRequest.Builder()
                    .setPreviewWidth(reqW)
                    .setPreviewHeight(reqH)
                    .setRenderMode(CameraRequest.RenderMode.OPENGL)
                    .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                    .setAudioSource(CameraRequest.AudioSource.NONE) // звук пишем отдельно с микрофона телефона
                    .setAspectRatioShow(false)
                    .create()
                cam.openCamera(SurfaceAspect(surface, reqW, reqH), request)
            } catch (e: Exception) {
                listener.onError(e.message ?: "USB camera error")
            }
        }

        val c = MultiCameraClient(appCtx, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                client?.requestPermission(device)
            }
            override fun onDetachDec(device: UsbDevice?) {
                connected = false
                listener.onDisconnected()
            }
            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                openCamera(device, ctrlBlock)
            }
            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                connected = false
                listener.onDisconnected()
            }
            override fun onCancelDev(device: UsbDevice?) {
                listener.onError(Loc.ctx(appCtx).getString(R.string.usb_permission_denied))
            }
        })
        client = c
        c.register()
        // камера уже могла быть подключена до запуска — список устройств не пуст, запрашиваем доступ сами
        c.getDeviceList(null)?.firstOrNull()?.let { c.requestPermission(it) }
    }

    fun stop() {
        try { camera?.closeCamera() } catch (_: Exception) {}
        camera = null
        try { client?.unRegister() } catch (_: Exception) {}
        try { client?.destroy() } catch (_: Exception) {}
        client = null
        connected = false
    }
}
