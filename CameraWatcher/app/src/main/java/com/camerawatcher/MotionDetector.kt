package com.camerawatcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Детектор движения по сетке клеток.
 *
 * На вход приходит уменьшенный кадр яркости (RGBA, порядок строк как в glReadPixels: снизу вверх),
 * по CELL_PX x CELL_PX пикселей на клетку. Для каждой клетки считается средняя яркость и сравнивается
 * с медленно обновляемым фоном. Общий сдвиг яркости (автоэкспозиция, включили свет) вычитается,
 * чтобы не давать ложных срабатываний.
 */
class MotionDetector {
    companion object {
        const val CELL_PX = 8
    }

    private var cols = 0
    private var rows = 0
    private var bg = FloatArray(0)
    private var mask = BooleanArray(0)
    private var sensitivity = 60
    private var warmup = 0

    /** Какие клетки в последнем кадре считались «движущимися» (порядок: сверху вниз, слева направо). */
    @Volatile
    var active = BooleanArray(0)
        private set

    @Synchronized
    fun configure(cols: Int, rows: Int, mask: BooleanArray, sensitivity: Int) {
        if (cols != this.cols || rows != this.rows) {
            this.cols = cols
            this.rows = rows
            bg = FloatArray(cols * rows)
            active = BooleanArray(cols * rows)
            warmup = 0
        }
        this.mask = if (mask.size == cols * rows) mask else BooleanArray(cols * rows)
        this.sensitivity = sensitivity
    }

    /** @return true, если в кадре есть движение. */
    @Synchronized
    fun analyze(px: ByteArray, w: Int, h: Int): Boolean {
        if (cols == 0 || w != cols * CELL_PX || h != rows * CELL_PX) return false
        val n = cols * rows
        val sum = FloatArray(n)
        for (y in 0 until h) {
            val cr = rows - 1 - y / CELL_PX // GL: строка 0 = низ кадра
            val rowBase = cr * cols
            val pBase = y * w * 4
            for (x in 0 until w) {
                sum[rowBase + x / CELL_PX] += (px[pBase + x * 4].toInt() and 0xFF).toFloat()
            }
        }
        val area = (CELL_PX * CELL_PX).toFloat()
        for (i in 0 until n) sum[i] /= area

        if (warmup < 6) { // первые кадры только строим фон
            for (i in 0 until n) bg[i] = if (warmup == 0) sum[i] else bg[i] * 0.5f + sum[i] * 0.5f
            warmup++
            java.util.Arrays.fill(active, false)
            return false
        }

        // общий сдвиг яркости
        var gm = 0f
        for (i in 0 until n) gm += sum[i] - bg[i]
        gm /= n

        val thr = 30f - 0.26f * sensitivity // 100 -> ~4, 1 -> ~30
        val act = BooleanArray(n)
        var enabled = 0
        var actCount = 0
        var changedAll = 0
        for (i in 0 until n) {
            val d = abs((sum[i] - bg[i]) - gm)
            val changed = d > thr
            if (changed) changedAll++
            if (!mask[i]) {
                enabled++
                if (changed) {
                    act[i] = true
                    actCount++
                }
            }
        }

        // резкая смена освещения во всём кадре — это не движение
        if (changedAll > n * 0.7f) {
            System.arraycopy(sum, 0, bg, 0, n)
            java.util.Arrays.fill(active, false)
            return false
        }

        val need = max(1, (enabled * (0.05f - 0.0004f * sensitivity)).roundToInt())
        val motion = enabled > 0 && actCount >= need
        active = act

        val rate = if (motion) 0.02f else 0.15f
        for (i in 0 until n) bg[i] += (sum[i] - bg[i]) * rate
        return motion
    }
}
