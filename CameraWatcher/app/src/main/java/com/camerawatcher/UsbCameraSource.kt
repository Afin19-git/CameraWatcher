package com.camerawatcher

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.uvc.UVCCamera
import com.jiangdg.usb.USBMonitor

/**
 * USB-веб-камера (UVC) по OTG вместо своей камеры телефона.
 *
 * Разрешение на USB и подключение/отключение отслеживает библиотека AndroidUSBCamera
 * (https://github.com/WojciechCzeronko/AndroidUSBCamera, через USBMonitor в MultiCameraClient).
 * Сам поток кадров идёт напрямую через UVCCamera (модуль libuvc): декодированные MJPEG-кадры
 * пишутся в наш Surface — тот же Surface, в который иначе пишет Camera2. Весь остальной конвейер
 * (GL, детектор движения, кодирование, надпись с датой) не меняется.
 *
 * Почему не через MultiCameraClient.ICamera: его путь OPENGL создаёт EGL на нашем Surface, который уже
 * занят конвейером (падение gl_render), а путь NORMAL требует настоящий View, которого у нас нет.
 *
 * Важно про разрешение на доступ к USB-устройству: это системный диалог Android. Если веб-камера
 * подключается первый раз, когда приложение работает в фоне, диалог может не появиться — поэтому
 * первое подключение делается в превью, после этого Android запоминает разрешение.
 *
 * Жизненный цикл: одна активная UVC-сессия за раз. Перед новым открытием предыдущая закрывается.
 * При отключении камеры нативные вызовы по её уже закрытому соединению не делаются.
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
    private var uvc: UVCCamera? = null
    private var activeDeviceId: Int? = null
    /** Устройство отключено: системное соединение уже закрыто, нативное закрытие по нему не вызываем. */
    private var deviceGone = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryScheduled = false
    /** Ключ (vendorId:productId) камеры, с которой работаем. Остальные UVC-устройства игнорируем. */
    private var lockedKey: String? = null
    @Volatile private var running = false
    @Volatile var connected = false
        private set

    private fun keyOf(d: UsbDevice) = "${d.vendorId}:${d.productId}"

    /** Закрывает текущую UVC-сессию. Безопасно вызывать многократно. */
    private fun releaseCamera() {
        val cam = uvc ?: return
        uvc = null
        activeDeviceId = null
        connected = false
        if (deviceGone) {
            // устройство уже отключено: только останавливаем поток кадров, который держит USB-захват.
            // close/destroy здесь нельзя (падение fdsan), поэтому дескриптор не трогаем.
            try { cam.stopPreview() } catch (_: Exception) {}
            return
        }
        try { cam.stopPreview() } catch (_: Exception) {}
        try { cam.close() } catch (_: Exception) {}
        try { cam.destroy() } catch (_: Exception) {}
    }

    private fun openCamera(
        surface: Surface,
        device: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock?,
        listener: Listener,
        w: Int,
        h: Int,
        fallbackLeft: Boolean,
        retriesLeft: Int,
    ) {
        if (!running) return
        releaseCamera()
        deviceGone = false
        if (ctrlBlock == null) {
            listener.onError("USB control block is null")
            return
        }
        val cam = UVCCamera()
        try {
            cam.open(ctrlBlock)
        } catch (e: Exception) {
            // destroy() здесь нельзя: после неудачного open нативный release закрывает дескриптор,
            // которым владеет USB-соединение, и fdsan аварийно завершает приложение. Объект просто отбрасываем.
            if (!running) return
            if (retriesLeft > 0) {
                // сразу после отключения USB-интерфейс может быть ещё занят — пробуем ещё раз позже
                retryScheduled = true
                mainHandler.postDelayed({
                    retryScheduled = false
                    openCamera(surface, device, ctrlBlock, listener, w, h, fallbackLeft, retriesLeft - 1)
                }, 2500L)
            } else {
                listener.onError("open camera failed: ${e.message}")
            }
            return
        }
        uvc = cam
        activeDeviceId = device.deviceId
        try {
            cam.setPreviewSize(
                w, h,
                UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                UVCCamera.FRAME_FORMAT_MJPEG,
                UVCCamera.DEFAULT_BANDWIDTH,
            )
        } catch (e: Exception) {
            releaseCamera()
            if (!running) return
            if (fallbackLeft && (w != 640 || h != 480)) {
                // камера не поддерживает запрошенный размер — пробуем запасной 640×480
                openCamera(surface, device, ctrlBlock, listener, 640, 480, false, retriesLeft)
            } else {
                listener.onError("unsupported preview size (${w}x$h)")
            }
            return
        }
        try {
            cam.setPreviewDisplay(surface)
            cam.startPreview()
        } catch (e: Exception) {
            releaseCamera()
            if (running) listener.onError("start preview failed: ${e.message}")
            return
        }
        if (!running) { releaseCamera(); return }
        connected = true
        listener.onReady(w, h)
    }

    /** Ищет UVC-камеру и направляет её кадры в [surface]. [reqW]/[reqH] — желаемый размер. */
    fun start(surface: Surface, reqW: Int, reqH: Int, listener: Listener) {
        stop()
        running = true
        val appCtx = ctx.applicationContext

        val c = MultiCameraClient(appCtx, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (!running) return
                if (lockedKey != null && keyOf(device) != lockedKey) return
                client?.requestPermission(device)
            }
            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                if (!running) return
                if (lockedKey != null && keyOf(device) != lockedKey) return
                deviceGone = true
                releaseCamera()
                listener.onDisconnected()
            }
            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                if (!running) return
                // работаем только с одной камерой: первая открытая задаёт ключ, остальные игнорируем
                if (lockedKey == null) lockedKey = keyOf(device)
                if (keyOf(device) != lockedKey) return
                // то же устройство уже открыто (повторное событие) — не открываем второй раз
                if (uvc != null && activeDeviceId == device.deviceId) return
                if (retryScheduled) return
                openCamera(surface, device, ctrlBlock, listener, reqW, reqH, true, 4)
            }
            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                if (!running) return
                if (lockedKey != null && keyOf(device) != lockedKey) return
                deviceGone = true
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
        retryScheduled = false
        lockedKey = null
        mainHandler.removeCallbacksAndMessages(null)
        releaseCamera()
        val c = client
        client = null
        try { c?.unRegister() } catch (_: Exception) {}
        try { c?.destroy() } catch (_: Exception) {}
        connected = false
    }
}
