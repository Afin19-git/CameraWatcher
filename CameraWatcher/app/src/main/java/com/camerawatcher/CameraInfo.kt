package com.camerawatcher

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecList
import android.util.Range
import android.util.Size
import kotlin.math.abs

object Codecs {
    const val AVC = "video/avc"
    const val HEVC = "video/hevc"

    /** Есть ли аппаратный HEVC-кодировщик. */
    fun hevcAvailable(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder &&
                ci.supportedTypes.any { it.equals(HEVC, ignoreCase = true) } &&
                !ci.name.startsWith("OMX.google.") && !ci.name.startsWith("c2.android.") &&
                !ci.name.contains(".sw.", ignoreCase = true)
        }
    } catch (e: Exception) { false }

    /** Какой кодек будем использовать с учётом настроек. */
    fun chooseMime(): String =
        if (Prefs.codecMode == "auto" && !Prefs.hevcFailed && hevcAvailable()) HEVC else AVC
}

/** Возможности основной (задней) камеры. */
class CameraInfo private constructor(ctx: Context) {
    val id: String
    val sensorOrientation: Int
    val fpsRanges: Array<Range<Int>>
    val sizes: List<Size>
    private val map: StreamConfigurationMap

    init {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var pick: String? = null
        for (cid in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(cid)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                pick = cid
                break
            }
        }
        id = pick ?: mgr.cameraIdList.firstOrNull() ?: throw IllegalStateException(Loc.ctx(ctx).getString(R.string.err_no_camera))
        val chars = mgr.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf(Range(15, 30))
        map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException(Loc.ctx(ctx).getString(R.string.err_no_sizes))
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val good = all.filter {
            val r = it.width.toFloat() / it.height
            it.width <= 1920 && it.height <= 1080 && it.width >= 640 &&
                (abs(r - 16f / 9f) < 0.02f || abs(r - 4f / 3f) < 0.02f)
        }
        sizes = (if (good.isNotEmpty()) good else all.filter { it.width <= 1920 }.ifEmpty { all })
            .sortedByDescending { it.width * it.height }
    }

    /** Максимальный FPS, который камера обещает для этого разрешения. */
    fun maxFps(size: Size): Int {
        val d = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        return if (d <= 0) 30 else (1_000_000_000.0 / d + 0.5).toInt()
    }

    /** Лучший диапазон AE для нужного FPS: сначала точный [fps,fps], потом самый узкий, содержащий fps. */
    fun rangeFor(fps: Int): Range<Int>? {
        fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return fpsRanges.filter { it.lower <= fps && it.upper >= fps }
            .sortedWith(compareBy({ it.upper - it.lower }, { it.upper }))
            .firstOrNull()
    }

    fun fpsSupported(size: Size, fps: Int): Boolean = rangeFor(fps) != null && fps <= maxFps(size) + 1

    companion object {
        val STANDARD_FPS = listOf(16, 24, 30, 60)
        fun get(ctx: Context) = CameraInfo(ctx.applicationContext)
    }
}

/**
 * Всё, что нужно знать о кадре.
 * rot — на сколько градусов по часовой нужно повернуть «сырой» кадр источника, чтобы он стоял ровно.
 * Для телефонной камеры определяется автоматически по акселерометру; для USB-камеры задаётся вручную
 * в настройках, так как веб-камера крепится отдельно от телефона и не поворачивается вместе с ним.
 * outW/outH — размер итогового видео: кадр целиком, без обрезки, в том соотношении, какое отдаёт источник.
 * fpsRange — только для Camera2 (телефон); для USB-источника не используется (null).
 */
data class Geometry(
    val camW: Int, val camH: Int,
    val rot: Int,
    val outW: Int, val outH: Int,
    val cols: Int, val rows: Int,
    val fps: Int, val fpsRange: Range<Int>?
)

object GeometryBuilder {
    /** Поворот и итоговый размер кадра из «сырого» размера источника — общая часть для телефона и USB. */
    private fun frame(rawW: Int, rawH: Int, rot: Int): Triple<Int, Int, Pair<Int, Int>> {
        val swap = rot == 90 || rot == 270
        val outW = (if (swap) rawH else rawW) and 1.inv()
        val outH = (if (swap) rawW else rawH) and 1.inv()
        val long = Prefs.gridLong
        val short = Prefs.gridShort
        val cols = if (outW >= outH) long else short
        val rows = if (outW >= outH) short else long
        return Triple(outW, outH, cols to rows)
    }

    /** Геометрия для телефонной камеры (Camera2): поворот — по акселерометру. */
    fun build(ctx: Context, displayRotationDeg: Int): Geometry {
        val ci = CameraInfo.get(ctx)
        val wantArea = Prefs.resW * Prefs.resH
        val size = ci.sizes.minByOrNull { abs(it.width * it.height - wantArea) } ?: Size(1280, 720)

        var fps = Prefs.fps
        if (!ci.fpsSupported(size, fps)) {
            fps = listOf(30, 24, 16).firstOrNull { ci.fpsSupported(size, it) } ?: 30
        }
        val range = ci.rangeFor(fps) ?: Range(fps, fps)

        val rot = (ci.sensorOrientation - displayRotationDeg + 360) % 360
        val (outW, outH, grid) = frame(size.width, size.height, rot)
        return Geometry(size.width, size.height, rot, outW, outH, grid.first, grid.second, fps, range)
    }

    /**
     * Геометрия для USB-веб-камеры: camW/camH — реально согласованный с камерой размер (известен только
     * после её подключения), поворот — ручная настройка Prefs.usbRotation. FPS берётся из настроек записи
     * (сама USB-камера частоту кадров через эту библиотеку не сообщает, кодируем с тем, что реально придёт).
     */
    fun buildUsb(camW: Int, camH: Int): Geometry {
        val rot = Prefs.usbRotation
        val (outW, outH, grid) = frame(camW, camH, rot)
        return Geometry(camW, camH, rot, outW, outH, grid.first, grid.second, Prefs.fps, null)
    }
}
