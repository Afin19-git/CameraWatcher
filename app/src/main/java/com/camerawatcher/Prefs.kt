package com.camerawatcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Loc.ctx(base))
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Drive.init(this)
    }
}

const val TAG = "CameraWatcher"
fun logE(msg: String, e: Throwable? = null) { Log.e(TAG, msg, e) }
fun logI(msg: String) { Log.i(TAG, msg) }

/** Все настройки приложения в одном месте. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("camerawatcher", Context.MODE_PRIVATE)
        }
    }

    private fun gi(k: String, d: Int) = sp.getInt(k, d)
    private fun pi(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun gs(k: String, d: String) = sp.getString(k, d) ?: d
    private fun ps(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun gb(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun gl(k: String, d: Long) = sp.getLong(k, d)
    private fun pl(k: String, v: Long) = sp.edit().putLong(k, v).apply()

    // ---- язык ----
    /** auto | en | ru | uk */
    var lang: String
        get() = gs("lang", "auto")
        set(v) = ps("lang", v)
    /** Язык имён папок на Drive (фиксируется при входе; для старых установок — русский). */
    var driveNamesLang: String
        get() = gs("driveNamesLang", "ru")
        set(v) = ps("driveNamesLang", v)

    // ---- детекция движения ----
    /** Длинная сторона сетки (по умолчанию 16). Для портретного кадра сетка транспонируется. */
    var gridLong: Int
        get() = gi("gridLong", 16)
        set(v) = pi("gridLong", v.coerceIn(4, 32))
    /** Короткая сторона сетки (по умолчанию 9). */
    var gridShort: Int
        get() = gi("gridShort", 9)
        set(v) = pi("gridShort", v.coerceIn(3, 32))
    var sensitivity: Int
        get() = gi("sensitivity", 60)
        set(v) = pi("sensitivity", v.coerceIn(1, 100))
    var postRollSec: Int
        get() = gi("postRollSec", 6)
        set(v) = pi("postRollSec", v.coerceIn(2, 60))
    /** Короткий звуковой сигнал (би-бип) при обнаружении движения — как звонок, не для записи. */
    var soundAlert: Boolean
        get() = gb("soundAlert", false)
        set(v) = pb("soundAlert", v)
    /** Не чаще, чем раз в столько секунд, пока движение продолжается. */
    var soundAlertCooldownSec: Int
        get() = gi("soundAlertCooldownSec", 5)
        set(v) = pi("soundAlertCooldownSec", v.coerceIn(1, 60))

    fun maskFor(cols: Int, rows: Int): BooleanArray {
        val arr = BooleanArray(cols * rows)
        if (gi("maskCols", 0) == cols && gi("maskRows", 0) == rows) {
            gs("maskCells", "").split(",").forEach { s ->
                s.toIntOrNull()?.let { if (it in arr.indices) arr[it] = true }
            }
        }
        return arr
    }

    fun saveMask(cols: Int, rows: Int, mask: BooleanArray) {
        val cells = mask.indices.filter { mask[it] }.joinToString(",")
        sp.edit().putInt("maskCols", cols).putInt("maskRows", rows).putString("maskCells", cells).apply()
    }

    // ---- источник камеры ----
    /** "phone" — своя камера телефона, "usb" — веб-камера по OTG. */
    var cameraSource: String
        get() = gs("cameraSource", "phone")
        set(v) = ps("cameraSource", v)
    /** Желаемое разрешение USB-камеры (реальное может отличаться — зависит от того, что поддерживает камера). */
    var usbResW: Int
        get() = gi("usbResW", 1280)
        set(v) = pi("usbResW", v)
    var usbResH: Int
        get() = gi("usbResH", 720)
        set(v) = pi("usbResH", v)
    /** Поворот USB-камеры задаётся вручную (0/90/180/270): она закреплена отдельно от телефона. */
    var usbRotation: Int
        get() = gi("usbRotation", 0)
        set(v) = pi("usbRotation", ((v % 360) + 360) % 360)

    // ---- запись ----
    var resW: Int
        get() = gi("resW", 1280)
        set(v) = pi("resW", v)
    var resH: Int
        get() = gi("resH", 720)
        set(v) = pi("resH", v)
    var fps: Int
        get() = gi("fps", 24)
        set(v) = pi("fps", v)
    var bitrateKbps: Int
        get() = gi("bitrateKbps", 1500)
        set(v) = pi("bitrateKbps", v)
    /** "auto" = HEVC если есть аппаратный кодер, иначе H.264; "h264" = всегда H.264 */
    var codecMode: String
        get() = gs("codecMode", "auto")
        set(v) = ps("codecMode", v)
    var mute: Boolean
        get() = gb("mute", false)
        set(v) = pb("mute", v)
    var label: String
        get() = gs("label", "")
        set(v) = ps("label", v)

    // ---- хранение ----
    /** Сколько дней хранить клипы-исходники (alarm_*.mp4) на Google Drive. */
    var driveKeepDaysClips: Int
        get() = gi("driveKeepDaysClips", 30)
        set(v) = pi("driveKeepDaysClips", v.coerceIn(1, 365))
    /** Сколько дней хранить таймлапсы на Google Drive (они лёгкие — срок обычно больше). */
    var driveKeepDaysTimelapse: Int
        get() = gi("driveKeepDaysTimelapse", 180)
        set(v) = pi("driveKeepDaysTimelapse", v.coerceIn(1, 3650))
    /** Сколько дней хранить клипы-исходники на самом телефоне. */
    var localKeepDaysClips: Int
        get() = gi("localKeepDaysClips", 2)
        set(v) = pi("localKeepDaysClips", v.coerceIn(1, 30))
    /** Сколько дней хранить таймлапсы на самом телефоне. */
    var localKeepDaysTimelapse: Int
        get() = gi("localKeepDaysTimelapse", 14)
        set(v) = pi("localKeepDaysTimelapse", v.coerceIn(1, 365))

    // ---- расписание записи (в минутах от полуночи) ----
    var schedEnabled: Boolean
        get() = gb("schedEnabled", false)
        set(v) = pb("schedEnabled", v)
    var schedStartMin: Int
        get() = gi("schedStartMin", 7 * 60)
        set(v) = pi("schedStartMin", v)
    var schedEndMin: Int
        get() = gi("schedEndMin", 21 * 60)
        set(v) = pi("schedEndMin", v)

    /** Можно ли сейчас записывать. Окно может переходить через полночь (например, 20:00–07:00). */
    fun scheduleAllowsNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!schedEnabled) return true
        val c = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val a = schedStartMin
        val b = schedEndMin
        return when {
            a == b -> true
            a < b -> m in a until b
            else -> m >= a || m < b
        }
    }

    // ---- таймлапс (в минутах от полуночи) ----
    var tlStartMin: Int
        get() = gi("tlStartMin", 4 * 60)
        set(v) = pi("tlStartMin", v)
    var tlEndMin: Int
        get() = gi("tlEndMin", 18 * 60)
        set(v) = pi("tlEndMin", v)
    var tlBuiltDate: String
        get() = gs("tlBuiltDate", "")
        set(v) = ps("tlBuiltDate", v)
    /** Собирать таймлапс за весь день (00:00–24:00), игнорируя окно «с/до». */
    var tlWholeDay: Boolean
        get() = gb("tlWholeDay", false)
        set(v) = pb("tlWholeDay", v)
    /** Желаемая длина итогового таймлапса, секунд. */
    var tlLengthSec: Int
        get() = gi("tlLengthSec", 60)
        set(v) = pi("tlLengthSec", v.coerceIn(5, 600))

    // ---- Google Drive ----
    /** Пользователь вошёл в Google через Play Services и дал доступ к Drive. */
    var driveSignedIn: Boolean
        get() = gb("driveSignedIn", false)
        set(v) = pb("driveSignedIn", v)
    var driveEmail: String
        get() = gs("driveEmail", "")
        set(v) = ps("driveEmail", v)
    /** Доступ пропал (отозван в аккаунте Google) — нужно войти заново. */
    var driveNeedsLogin: Boolean
        get() = gb("driveNeedsLogin", false)
        set(v) = pb("driveNeedsLogin", v)

    fun driveFolder(path: String): String? = try {
        JSONObject(gs("driveFolders", "{}")).optString(path, "").ifEmpty { null }
    } catch (e: Exception) { null }

    fun putDriveFolder(path: String, id: String) {
        val o = try { JSONObject(gs("driveFolders", "{}")) } catch (e: Exception) { JSONObject() }
        o.put(path, id)
        ps("driveFolders", o.toString())
    }

    fun clearDriveFolders() = ps("driveFolders", "{}")

    // ---- служебные флаги ----
    /** Пользователь запустил камеру и не останавливал её (нужен watchdog'у). */
    var desiredRunning: Boolean
        get() = gb("desiredRunning", false)
        set(v) = pb("desiredRunning", v)
    var heartbeat: Long
        get() = gl("heartbeat", 0L)
        set(v) = pl("heartbeat", v)
    var lowDevice: Boolean
        get() = gb("lowDevice", false)
        set(v) = pb("lowDevice", v)
    var lowDrive: Boolean
        get() = gb("lowDrive", false)
        set(v) = pb("lowDrive", v)
    var hevcFailed: Boolean
        get() = gb("hevcFailed", false)
        set(v) = pb("hevcFailed", v)
    var pinAdviceShown: Boolean
        get() = gb("pinAdviceShown", false)
        set(v) = pb("pinAdviceShown", v)
    var codecPopupShown: Boolean
        get() = gb("codecPopupShown", false)
        set(v) = pb("codecPopupShown", v)
    var batteryAsked: Boolean
        get() = gb("batteryAsked", false)
        set(v) = pb("batteryAsked", v)
}

object Fmt {
    private fun f(p: String) = SimpleDateFormat(p, Locale.US)
    fun date(ms: Long): String = f("yyyy-MM-dd").format(Date(ms))
    fun stamp(ms: Long): String = f("yyyyMMdd_HHmmss").format(Date(ms))
    fun overlay(ms: Long): String = f("dd.MM.yyyy HH:mm:ss").format(Date(ms))
    fun hm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}



object Storage {
    fun root(ctx: Context): File {
        val d = ctx.getExternalFilesDir("clips") ?: File(ctx.filesDir, "clips")
        d.mkdirs()
        return d
    }

    fun dayDir(ctx: Context, ms: Long): File = File(root(ctx), Fmt.date(ms)).also { it.mkdirs() }

    fun freeBytes(ctx: Context): Long = try {
        StatFs(root(ctx).path).availableBytes
    } catch (e: Exception) { Long.MAX_VALUE }

    /** Все готовые .mp4 (без .part) во всех папках дней. */
    fun allVideos(ctx: Context): List<File> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.flatMap { d ->
            d.listFiles { f -> f.name.endsWith(".mp4") }?.toList() ?: emptyList()
        } ?: emptyList()

    /** Время начала клипа из имени alarm_yyyyMMdd_HHmmss.mp4 */
    fun clipTime(f: File): Long? {
        val m = Regex("alarm_(\\d{8})_(\\d{6})").find(f.name) ?: return null
        return try {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(m.groupValues[1] + m.groupValues[2])?.time
        } catch (e: Exception) { null }
    }
}

object Net {
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
