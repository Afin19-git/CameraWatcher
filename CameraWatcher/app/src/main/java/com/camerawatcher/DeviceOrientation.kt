package com.camerawatcher

import android.content.Context
import android.view.OrientationEventListener

/**
 * Как физически повёрнут телефон прямо сейчас — по акселерометру, а не по повороту экрана
 * (экран у нас заблокирован в одном положении, а телефон может быть закреплён как угодно).
 *
 * Значение в том же виде, что и Surface.ROTATION_* * 90 (0/90/180/270) — как раньше отдавал
 * windowManager.defaultDisplay.rotation, поэтому дальше по коду ничего не меняется.
 *
 * Работает непрерывно, пока хоть один потребитель вызвал [start] и не вызвал [stop] в ответ
 * (счётчик ссылок) — так несколько экранов/движков могут делить один датчик.
 */
object DeviceOrientation {
    @Volatile private var deg = 0
    @Volatile private var haveReading = false
    private var listener: OrientationEventListener? = null
    private var refCount = 0
    private val lock = Any()

    fun start(ctx: Context) {
        synchronized(lock) {
            refCount++
            if (listener != null) return
            val l = object : OrientationEventListener(ctx.applicationContext, android.hardware.SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    deg = when {
                        orientation in 45 until 135 -> 270
                        orientation in 135 until 225 -> 180
                        orientation in 225 until 315 -> 90
                        else -> 0
                    }
                    haveReading = true
                }
            }
            if (l.canDetectOrientation()) {
                l.enable()
                listener = l
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount > 0) return
            listener?.disable()
            listener = null
            haveReading = false
        }
    }

    /** Последнее известное значение (0/90/180/270). Пока не было ни одного замера — 0 (как обычный портрет). */
    fun current(): Int = deg

    /** Разовый замер с ожиданием первого результата — для старта записи, когда крутить телефон уже некому. */
    fun sampleDeg(ctx: Context, timeoutMs: Long = 700): Int {
        start(ctx)
        try {
            val end = System.currentTimeMillis() + timeoutMs
            while (!haveReading && System.currentTimeMillis() < end) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        } finally {
            stop()
        }
        return deg
    }
}
