package com.camerawatcher

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Один клип: видео (H.264/HEVC с Surface на входе) + звук (AAC) -> MP4.
 * Пишем в файл *.mp4.part, по окончании переименовываем в *.mp4 — так загрузчик не схватит недописанный файл.
 *
 * Видеокадры рисует GL-поток (см. CameraEngine), он же вызывает [drain]. Звук пишется в своём потоке.
 */
class VideoRecorder(
    val finalFile: File,
    val mime: String,
    private val w: Int,
    private val h: Int,
    private val fps: Int,
    private val bitrate: Int,
    withAudio: Boolean
) {
    private val partFile = File(finalFile.path + ".part")
    private var vCodec: MediaCodec? = null
    private var aCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioRec: AudioRecord? = null
    lateinit var inputSurface: Surface
        private set

    private val lock = Any()
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private var hasAudio = withAudio
    private class Sample(val audio: Boolean, val data: ByteArray, val flags: Int, val pts: Long)
    private val pending = ArrayList<Sample>()
    private val vInfo = MediaCodec.BufferInfo()

    @Volatile private var firstWallNs = 0L
    @Volatile private var audioStop = false
    @Volatile private var videoEos = false
    private var audioThread: Thread? = null

    private fun newVideoCodec(withMode: Boolean): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(mime, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // ключевой кадр раз в секунду (нужен для таймлапса)
        if (withMode) {
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val c = MediaCodec.createEncoderByType(mime)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    /** Создаёт кодек, муксер и запускает запись звука. Бросает исключение, если что-то не получилось. */
    fun start() {
        partFile.parentFile?.mkdirs()
        var c: MediaCodec
        try {
            c = newVideoCodec(true)
        } catch (e: Exception) {
            c = newVideoCodec(false) // некоторые кодеры не любят BITRATE_MODE
        }
        vCodec = c
        inputSurface = c.createInputSurface()
        c.start()
        muxer = MediaMuxer(partFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (hasAudio) hasAudio = startAudio()
    }

    /** Вызывается GL-потоком, когда первый видеокадр отправлен в кодек. */
    fun markFirstFrame() {
        firstWallNs = System.nanoTime()
    }

    // ---------------- звук ----------------
    private fun startAudio(): Boolean {
        val rate = 44100
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, 16384) * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            rec.startRecording()
            audioRec = rec
            aCodec = ac
            audioThread = Thread({ audioLoop(rec, ac, rate) }, "cw-audio").also { it.start() }
            return true
        } catch (e: Exception) {
            logE("audio init failed", e)
            return false
        }
    }

    private fun audioLoop(rec: AudioRecord, ac: MediaCodec, rate: Int) {
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(2048)
        var samples = 0L
        var base = -1L
        var eosSent = false
        var eosDone = false
        try {
            while (!eosDone) {
                if (!eosSent) {
                    if (audioStop) {
                        val idx = ac.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            ac.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    } else {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0 && firstWallNs != 0L) { // звук до первого кадра видео выбрасываем
                            val chunkUs = (n / 2) * 1_000_000L / rate
                            if (base < 0) base = max(0L, (System.nanoTime() - firstWallNs) / 1000 - chunkUs)
                            val pts = base + samples * 1_000_000L / rate
                            samples += n / 2
                            val idx = ac.dequeueInputBuffer(10_000)
                            if (idx >= 0) {
                                val ib = ac.getInputBuffer(idx)!!
                                ib.clear()
                                ib.put(buf, 0, n)
                                ac.queueInputBuffer(idx, 0, n, pts, 0)
                            }
                        }
                    }
                }
                // забираем готовый AAC
                while (true) {
                    val oi = ac.dequeueOutputBuffer(info, 0)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) {
                            aTrack = muxer!!.addTrack(ac.outputFormat)
                            tryStartMuxer()
                        }
                    } else if (oi >= 0) {
                        val ob = ac.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) writeSample(true, ob, info)
                        ac.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosDone = true
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE("audio loop", e)
            synchronized(lock) { // не даём муксеру ждать звук вечно
                hasAudio = false
                tryStartMuxer()
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { ac.stop() } catch (_: Exception) {}
            ac.release()
        }
    }

    // ---------------- муксер ----------------
    private fun tryStartMuxer() { // вызывать под lock
        if (muxStarted || vTrack < 0) return
        if (hasAudio && aTrack < 0) return
        muxer!!.start()
        muxStarted = true
        for (s in pending) writeNow(s.audio, ByteBuffer.wrap(s.data), s.flags, s.pts, s.data.size, 0)
        pending.clear()
    }

    private val tmpInfo = MediaCodec.BufferInfo()
    private fun writeNow(audio: Boolean, bb: ByteBuffer, flags: Int, pts: Long, size: Int, offset: Int) {
        tmpInfo.set(offset, size, pts, flags)
        bb.position(offset)
        bb.limit(offset + size)
        muxer!!.writeSampleData(if (audio) aTrack else vTrack, bb, tmpInfo)
    }

    private fun writeSample(audio: Boolean, bb: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (muxStarted) {
                writeNow(audio, bb, info.flags, info.presentationTimeUs, info.size, info.offset)
            } else {
                val arr = ByteArray(info.size)
                bb.position(info.offset)
                bb.limit(info.offset + info.size)
                bb.get(arr)
                pending.add(Sample(audio, arr, info.flags, info.presentationTimeUs))
                if (pending.size > 400) { // звук так и не запустился — пишем без него
                    hasAudio = false
                    tryStartMuxer()
                }
            }
        }
    }

    // ---------------- видео ----------------
    /** Забирает готовые видеопакеты из кодека. Вызывает GL-поток после каждого кадра. */
    fun drain(timeoutUs: Long = 0) {
        val c = vCodec ?: return
        while (true) {
            val oi = c.dequeueOutputBuffer(vInfo, timeoutUs)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    vTrack = muxer!!.addTrack(c.outputFormat)
                    tryStartMuxer()
                }
            } else if (oi >= 0) {
                val ob = c.getOutputBuffer(oi)!!
                if (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) vInfo.size = 0
                if (vInfo.size > 0) writeSample(false, ob, vInfo)
                c.releaseOutputBuffer(oi, false)
                if (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    videoEos = true
                    break
                }
            }
        }
    }

    /** Завершает запись: EOS, дописать хвост, закрыть файл. @return true, если файл получился. */
    fun finish(): Boolean {
        var ok = false
        audioStop = true
        try {
            vCodec?.signalEndOfInputStream()
            val end = SystemClock.elapsedRealtime() + 2000
            while (!videoEos && SystemClock.elapsedRealtime() < end) drain(10_000)
        } catch (e: Exception) {
            logE("finish video", e)
        }
        try { audioThread?.join(2500) } catch (_: Exception) {}
        synchronized(lock) {
            try {
                if (muxStarted) {
                    muxer?.stop()
                    ok = true
                }
            } catch (e: Exception) {
                logE("muxer stop", e)
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
        }
        try { vCodec?.stop() } catch (_: Exception) {}
        try { vCodec?.release() } catch (_: Exception) {}
        vCodec = null
        try { inputSurface.release() } catch (_: Exception) {}
        if (ok && partFile.length() > 0) {
            ok = partFile.renameTo(finalFile)
        } else {
            partFile.delete()
            ok = false
        }
        return ok
    }

    /** Аварийная остановка без сохранения (например, если кодек не запустился). */
    fun abort() {
        audioStop = true
        try { audioThread?.join(1000) } catch (_: Exception) {}
        try { vCodec?.stop() } catch (_: Exception) {}
        try { vCodec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { audioRec?.release() } catch (_: Exception) {}
        vCodec = null
        muxer = null
        partFile.delete()
    }
}
