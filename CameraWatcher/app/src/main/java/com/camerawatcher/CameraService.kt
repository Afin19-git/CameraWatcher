package com.camerawatcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Фоновые задачи: загрузка на Drive, очистка старых файлов, сборка таймлапса. Всё в одном потоке. */
class Housekeeper(private val ctx: Context) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var lastLocalClean = 0L
    private var lastDriveClean = 0L
    private var lastQuota = 0L
    private var checkedYesterday = false
    private var lastAuthWarn = 0L

    fun start() {
        exec.scheduleWithFixedDelay({ safe { runOnce() } }, 3, 60, TimeUnit.SECONDS)
    }

    fun stop() {
        exec.shutdownNow()
    }

    /** Запустить внеочередной проход (например, после сохранения клипа). */
    fun kick() {
        try { exec.execute { safe { runOnce() } } } catch (_: Exception) {}
    }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { logE("housekeeper", e) }
    }

    private fun runOnce() {
        val now = System.currentTimeMillis()
        if (now - lastLocalClean > 60 * 60_000L || Prefs.lowDevice) {
            cleanupLocal(now)
            lastLocalClean = now
        }
        timelapseCheck(now)
        if (Drive.isConfigured() && Net.online(ctx)) {
            try {
                if (now - lastQuota > 30 * 60_000L) {
                    val free = Drive.freeBytes()
                    Prefs.lowDrive = free != null && free < 100L * 1024 * 1024
                    lastQuota = now
                }
                uploadPending(now)
                if (now - lastDriveClean > 3 * 60 * 60_000L) {
                    Drive.deleteOld(Prefs.driveKeepDaysClips, Prefs.driveKeepDaysTimelapse)
                    lastDriveClean = now
                }
            } catch (e: DriveFullException) {
                Prefs.lowDrive = true
            } catch (e: DriveAuthException) {
                logE("drive auth", e)
                if (now - lastAuthWarn > 6 * 60 * 60_000L) { // напоминаем не чаще раза в 6 часов
                    lastAuthWarn = now
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "cw_warn") else Notification.Builder(ctx)
                    b.setSmallIcon(R.drawable.ic_stat).setContentTitle("CameraWatcher")
                        .setContentText(Loc.ctx(ctx).getString(R.string.n_no_drive_access))
                        .setAutoCancel(true)
                    nm.notify(3, b.build())
                }
            }
        }
    }

    /** Локально клипы и таймлапсы храним разное число дней (клипы тяжёлые, таймлапсы лёгкие). */
    private fun cleanupLocal(now: Long) {
        val limitClips = now - Prefs.localKeepDaysClips * 24L * 3600 * 1000
        val limitTl = now - Prefs.localKeepDaysTimelapse * 24L * 3600 * 1000
        val root = Storage.root(ctx)
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            d.listFiles()?.forEach { f ->
                val limit = if (f.name.startsWith("timelapse_")) limitTl else limitClips
                if (f.lastModified() < limit) f.delete()
            }
            if (d.listFiles().isNullOrEmpty()) d.delete()
        }
    }

    private fun uploadPending(now: Long) {
        val files = Storage.allVideos(ctx)
            .filter { !File(it.path + ".up").exists() && now - it.lastModified() > 5000 }
            .sortedBy { it.name }
        for (f in files) {
            if (!Net.online(ctx)) return
            val date = f.parentFile?.name ?: continue
            val parts = if (f.name.startsWith("timelapse_")) {
                listOf(Drive.rootName(), date)
            } else {
                val t = Storage.clipTime(f) ?: f.lastModified()
                val h = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
                listOf(Drive.rootName(), date, DriveNames.part(Prefs.driveNamesLang, h))
            }
            Drive.upload(f, parts)
            File(f.path + ".up").createNewFile() // локальный файл остаётся — это запасная копия
            logI("uploaded ${f.name}")
        }
    }

    private fun timelapseCheck(now: Long) {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = Fmt.date(now)
        val triggerMin = if (Prefs.tlWholeDay) 23 * 60 + 55 else Prefs.tlEndMin
        if (nowMin >= triggerMin && Prefs.tlBuiltDate != today) {
            buildTimelapse(today)
            Prefs.tlBuiltDate = today
        }
        if (!checkedYesterday) {
            checkedYesterday = true
            buildTimelapse(Fmt.date(now - 24L * 3600 * 1000))
        }
    }

    /** Собирает таймлапс за указанную дату (yyyy-MM-dd). @return true, если файл создан. */
    fun buildTimelapse(date: String, force: Boolean = false, kickAfter: Boolean = true): Boolean {
        val dir = File(Storage.root(ctx), date)
        if (!dir.isDirectory) return false
        val out = File(dir, "timelapse_$date.mp4")
        if (out.exists() && !force) return false
        var start = if (Prefs.tlWholeDay) 0 else Prefs.tlStartMin
        var end = if (Prefs.tlWholeDay) 24 * 60 else Prefs.tlEndMin
        if (end <= start) { val t = start; start = end; end = t }
        val clips = dir.listFiles { f -> f.name.startsWith("alarm_") && f.name.endsWith(".mp4") }
            ?.filter {
                val t = Storage.clipTime(it) ?: return@filter false
                val c = Calendar.getInstance().apply { timeInMillis = t }
                val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                m in start until end
            }?.sortedBy { it.name } ?: emptyList()
        if (clips.isEmpty()) return false
        if (out.exists()) { out.delete(); File(out.path + ".up").delete() }
        val ok = Timelapse.build(clips, out, targetSec = Prefs.tlLengthSec)
        logI("timelapse $date ok=$ok clips=${clips.size}")
        if (ok && kickAfter) kick()
        return ok
    }
}

class CameraService : Service(), CameraEngine.Listener {

    companion object {
        const val ACTION_START = "com.camerawatcher.START"
        const val ACTION_STOP = "com.camerawatcher.STOP"
        private const val CH_RUN = "cw_run"
        private const val CH_WARN = "cw_warn"
        private const val ID_RUN = 1
        private const val ID_WARN = 2

        @Volatile var engine: CameraEngine? = null
        @Volatile var housekeeper: Housekeeper? = null
        /** UI: сервис вот-вот откроет камеру — превью нужно отпустить. Вызывается в главном потоке. */
        @Volatile var onBeforeEngineStart: (() -> Unit)? = null
        /** UI: движок сервиса создан или остановлен (расписание, кнопка «Закрыть»). Главный поток. */
        @Volatile var onEngineChanged: (() -> Unit)? = null
        @Volatile private var instance: CameraService? = null

        /** Пользователь запустил камеру (сервис жив), даже если сейчас ночной режим и камера выключена. */
        val active: Boolean get() = instance != null
        /** Сервис жив, но камера выключена расписанием. */
        val sleeping: Boolean get() = instance != null && engine == null

        /** Проверить расписание прямо сейчас (зовёт watchdog: он будит телефон раз в ~30 секунд). */
        fun pokeSchedule() { instance?.applySchedule() }

        fun start(ctx: Context) {
            Prefs.desiredRunning = true
            val i = Intent(ctx, CameraService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Осознанная остановка пользователем: watchdog после этого не перезапускает. */
        fun stop(ctx: Context) {
            Prefs.desiredRunning = false
            Watchdog.cancel(ctx)
            val s = instance
            if (s != null) s.shutdown() else Prefs.heartbeat = 0
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastRec = false
    private var sleepingNow = false

    private val scheduleTick = object : Runnable {
        override fun run() {
            applySchedule()
            main.postDelayed(this, 30_000)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            Prefs.heartbeat = System.currentTimeMillis()
            main.postDelayed(this, 15_000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Loc.ctx(newBase))
    }

    /** Строка на текущем языке приложения (язык можно сменить, пока сервис работает). */
    private fun str(id: Int, vararg a: Any): String = Loc.ctx(applicationContext).getString(id, *a)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.desiredRunning = false
            Watchdog.cancel(this)
            shutdown()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs.desiredRunning) { // системный перезапуск, а пользователь камеру уже остановил
            stopSelf()
            return START_NOT_STICKY
        }
        startWork()
        return START_STICKY
    }

    private fun startWork() {
        instance = this
        createChannels()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                ID_RUN, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(ID_RUN, n)
        }
        if (housekeeper != null) return // уже работаем

        Prefs.lowDevice = false
        val hk = Housekeeper(this)
        housekeeper = hk
        hk.start()

        Prefs.heartbeat = System.currentTimeMillis()
        main.removeCallbacks(heartbeat)
        main.postDelayed(heartbeat, 15_000)
        Watchdog.schedule(this)

        applySchedule() // поднимет камеру, если сейчас разрешённое время
        main.removeCallbacks(scheduleTick)
        main.postDelayed(scheduleTick, 30_000)
    }

    /** Включает или выключает камеру по расписанию записи. Главный поток. */
    fun applySchedule() {
        if (instance == null) return
        val allowed = Prefs.scheduleAllowsNow()
        if (allowed && engine == null) startEngine()
        else if (!allowed && engine != null) stopEngine()
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraWatcher:run").also { it.acquire() }
    }

    private fun releaseWake() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
    }

    private fun startEngine() {
        onBeforeEngineStart?.invoke() // экран с превью отпускает камеру
        try {
            acquireWake()
            val rot = if (Prefs.cameraSource == "usb") 0 else DeviceOrientation.sampleDeg(this)
            val e = CameraEngine(this, true, rot)
            e.addListener(this)
            engine = e
            e.start()
            sleepingNow = false
            lastRec = false
        } catch (ex: Exception) {
            logE("startEngine", ex)
            releaseWake()
            onWarning(str(R.string.w_camera_start_failed, ex.message ?: ""))
        }
        refreshNotification()
        onEngineChanged?.invoke()
    }

    private fun stopEngine() {
        val e = engine
        engine = null
        e?.stop() // дописывает текущий клип и полностью освобождает камеру
        releaseWake() // ночью телефону даём спать
        sleepingNow = true
        lastRec = false
        refreshNotification()
        onEngineChanged?.invoke()
    }

    private fun shutdown() {
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(scheduleTick)
        val e = engine
        engine = null
        e?.stop()
        housekeeper?.stop()
        housekeeper = null
        releaseWake()
        instance = null
        stopForeground(true)
        stopSelf()
        main.post { onEngineChanged?.invoke() }
    }

    override fun onDestroy() {
        // Если система убила нас сама, desiredRunning остаётся true — watchdog поднимет сервис.
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(scheduleTick)
        engine?.stop()
        engine = null
        housekeeper?.stop()
        housekeeper = null
        releaseWake()
        instance = null
        super.onDestroy()
    }

    // ---- события движка ----
    override fun onAnalysis(cols: Int, rows: Int, active: BooleanArray, motion: Boolean, recording: Boolean) {
        if (recording != lastRec) {
            lastRec = recording
            refreshNotification()
        }
    }

    override fun onWarning(msg: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_WARN) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("CameraWatcher").setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg)).setAutoCancel(true)
        nm.notify(ID_WARN, b.build())
    }

    override fun onClipSaved(file: File) {
        housekeeper?.kick()
    }

    // ---- уведомление ----
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUN, str(R.string.ch_run), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WARN, str(R.string.ch_warn), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_RUN, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_RUN) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(str(R.string.n_title))
            .setContentText(
                when {
                    sleepingNow -> str(R.string.n_night, Fmt.hm(Prefs.schedStartMin))
                    lastRec -> str(R.string.n_recording)
                    else -> str(R.string.n_watching)
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, str(R.string.n_close), stop).build())
        return b.build()
    }
}

/** Просыпается раз в ~30 с. Если сервис должен работать, но не подаёт признаков жизни — запускает заново. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.desiredRunning) return // пользователь остановил камеру сам — не трогаем
        Watchdog.schedule(context)
        if (CameraService.active) { // сервис жив (ночью телефон спит, поэтому его собственный таймер может запаздывать)
            Prefs.heartbeat = System.currentTimeMillis()
            CameraService.pokeSchedule()
            return
        }
        val stale = System.currentTimeMillis() - Prefs.heartbeat > 45_000
        if (stale) {
            logI("watchdog: service is dead, restarting")
            try { CameraService.start(context) } catch (e: Exception) { logE("watchdog start", e) }
        }
    }
}

object Watchdog {
    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + 30_000
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx))
    }
}
