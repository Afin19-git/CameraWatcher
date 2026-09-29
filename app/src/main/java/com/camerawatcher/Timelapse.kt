package com.camerawatcher

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Таймлапс без перекодирования: берём из клипов только ключевые кадры (кодер ставит их раз в секунду)
 * и склеиваем в новый MP4 с быстрой частотой кадров. Это очень быстро и почти не греет телефон.
 */
object Timelapse {
    private fun videoTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("video/")) return i
        }
        return -1
    }

    private fun compatible(a: MediaFormat, b: MediaFormat): Boolean =
        a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            a.getInteger(MediaFormat.KEY_WIDTH) == b.getInteger(MediaFormat.KEY_WIDTH) &&
            a.getInteger(MediaFormat.KEY_HEIGHT) == b.getInteger(MediaFormat.KEY_HEIGHT)

    /**
     * @param clips клипы в хронологическом порядке
     * @param targetSec желаемая длина итогового видео (примерно)
     * @return true, если файл создан
     */
    fun build(clips: List<File>, out: File, targetSec: Int = 60, maxFps: Int = 30, minFps: Int = 5): Boolean {
        var ref: MediaFormat? = null
        val usable = ArrayList<File>()
        var total = 0

        // проход 1: сколько всего ключевых кадров
        for (f in clips) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.path)
                val t = videoTrack(ex)
                if (t < 0) continue
                val fmt = ex.getTrackFormat(t)
                if (ref == null) ref = fmt else if (!compatible(ref, fmt)) continue
                ex.selectTrack(t)
                var n = 0
                do {
                    if (ex.sampleTime >= 0 && (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) n++
                } while (ex.advance())
                if (n > 0) {
                    usable.add(f)
                    total += n
                }
            } catch (e: Exception) {
                logE("timelapse scan ${f.name}", e)
            } finally {
                ex.release()
            }
        }
        if (total == 0 || ref == null) return false

        val wanted = minOf(total, targetSec * maxFps)
        val fps = (wanted.toDouble() / targetSec).coerceIn(minFps.toDouble(), maxFps.toDouble())

        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        val muxer = MediaMuxer(part.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(ref)
        muxer.start()
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var syncIdx = 0L
        var outIdx = 0

        try {
            // проход 2: выбираем равномерно wanted кадров из total
            for (f in usable) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.path)
                    val t = videoTrack(ex)
                    ex.selectTrack(t)
                    do {
                        if (ex.sampleTime >= 0 && (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            val pick = (syncIdx + 1) * wanted / total > syncIdx * wanted / total
                            syncIdx++
                            if (pick) {
                                buf.clear()
                                val n = ex.readSampleData(buf, 0)
                                if (n > 0) {
                                    val pts = (outIdx * 1_000_000.0 / fps).toLong()
                                    info.set(0, n, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                    muxer.writeSampleData(track, buf, info)
                                    outIdx++
                                }
                            }
                        }
                    } while (ex.advance())
                } catch (e: Exception) {
                    logE("timelapse read ${f.name}", e)
                } finally {
                    ex.release()
                }
            }
        } finally {
            try { muxer.stop() } catch (e: Exception) { logE("timelapse stop", e) }
            muxer.release()
        }
        if (outIdx == 0) {
            part.delete()
            return false
        }
        return part.renameTo(out)
    }
}
