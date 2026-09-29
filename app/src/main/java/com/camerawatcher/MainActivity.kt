package com.camerawatcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback, CameraEngine.Listener {

    private lateinit var surface: SurfaceView
    private lateinit var overlay: GridOverlayView
    private lateinit var frame: AspectFrame
    private lateinit var status: TextView
    private lateinit var gridInfo: TextView
    private lateinit var sensLabel: TextView
    private lateinit var startBtn: TextView
    private lateinit var maskBtn: TextView
    private lateinit var hint: TextView

    private val main = Handler(Looper.getMainLooper())
    private var engine: CameraEngine? = null
    private var ownsEngine = false
    private var surfaceReady = false
    private var starting = false

    // ------------------------------------------------------------ создание
    private var langAtCreate = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Loc.ctx(newBase))
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        langAtCreate = Prefs.lang
        // Ориентацию фиксируем как есть: телефон обычно стоит на месте, а поворот кадра считается один раз при запуске.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        buildUi()
        requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(col)

        // заголовок
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = text("CameraWatcher", 22f, Theme.TEXT, true)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)
        val settingsBtn = text(getString(R.string.btn_settings), 15f, Theme.ACCENT, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg(Theme.CARD, 12, this@MainActivity)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        header.addView(settingsBtn)
        col.addView(header)

        // статус
        status = text("", 14f, Theme.TEXT, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        col.addView(status)

        // превью + сетка
        frame = AspectFrame(this).apply {
            setBackgroundColor(Color.BLACK)
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        }
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        overlay = GridOverlayView(this)
        overlay.onMaskChanged = { m ->
            val e = engine
            if (e != null) {
                Prefs.saveMask(e.geometry.cols, e.geometry.rows, m)
                e.reloadConfig()
            }
        }
        frame.addView(surface)
        frame.addView(overlay)
        col.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // детекция
        val det = card()
        val sensRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sensTitle = text(getString(R.string.sensitivity), 15f)
        sensTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sensLabel = text("${Prefs.sensitivity}", 15f, Theme.ACCENT, true)
        sensRow.addView(sensTitle)
        sensRow.addView(sensLabel)
        det.addView(sensRow)
        val seek = SeekBar(this).apply {
            max = 99
            progress = Prefs.sensitivity - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    Prefs.sensitivity = p + 1
                    sensLabel.text = "${p + 1}"
                    engine?.reloadConfig()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        det.addView(seek)
        gridInfo = text("", 13f, Theme.MUTED)
        gridInfo.setPadding(0, dp(8), 0, 0)
        det.addView(gridInfo)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        maskBtn = button(getString(R.string.mask_off), Theme.CARD2, Theme.TEXT) { toggleMaskMode() }
        val clearBtn = button(getString(R.string.mask_reset), Theme.CARD2, Theme.TEXT) { clearMask() }
        for ((i, b) in listOf(maskBtn, clearBtn).withIndex()) {
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(10)
                if (i == 0) rightMargin = dp(6) else leftMargin = dp(6)
            }
            btnRow.addView(b)
        }
        det.addView(btnRow)
        det.addView(text(getString(R.string.mask_help), 12f, Theme.MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(det)

        // звук
        val snd = card()
        val sw = Switch(this).apply {
            text = getString(R.string.record_audio)
            setTextColor(Theme.TEXT)
            textSize = 15f
            isChecked = !Prefs.mute
            setOnCheckedChangeListener { _, on -> Prefs.mute = !on }
        }
        snd.addView(sw)
        snd.addView(text(getString(R.string.audio_note), 12f, Theme.MUTED))
        col.addView(snd)

        // старт/стоп
        startBtn = button("", Theme.ACCENT) { onStartStop() }
        col.addView(startBtn)
        hint = text("", 13f, Theme.MUTED).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(hint)

        setContentView(scroll)
    }

    // ------------------------------------------------------------ разрешения
    private fun neededPermissions(): Array<String> {
        val l = arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasCamera()) attach() else updateUi()
    }

    // ------------------------------------------------------------ жизненный цикл
    override fun onStart() {
        super.onStart()
        if (Prefs.lang != langAtCreate) { // язык сменили в настройках — перестраиваем экран
            recreate()
            return
        }
        CameraService.onBeforeEngineStart = { detach() }
        CameraService.onEngineChanged = {
            detach()
            attach()
            updateUi()
        }
        if (hasCamera()) attach()
        updateUi()
    }

    override fun onStop() {
        CameraService.onEngineChanged = null
        CameraService.onBeforeEngineStart = null
        detach()
        super.onStop()
    }

    // ------------------------------------------------------------ подключение к движку
    private fun attach() {
        if (engine != null) return
        val svc = CameraService.engine
        val e: CameraEngine
        try {
            if (svc != null && !svc.stopped) {
                e = svc
                ownsEngine = false
            } else {
                e = CameraEngine(this, false, DeviceOrientation.sampleDeg(this))
                ownsEngine = true
                e.start()
            }
        } catch (ex: Exception) {
            logE("attach", ex)
            status.text = getString(R.string.camera_unavailable, ex.message ?: "")
            return
        }
        engine = e
        e.addListener(this)
        applyGeometry(e.geometry)
        if (surfaceReady) e.setPreviewSurface(surface.holder.surface)
        updateUi()
    }

    private fun detach() {
        val e = engine ?: return
        e.removeListener(this)
        e.setPreviewSurface(null)
        if (ownsEngine) e.stop()
        engine = null
        ownsEngine = false
    }

    private fun applyGeometry(g: Geometry) {
        frame.ratio = g.outW.toFloat() / g.outH
        overlay.setGrid(g.cols, g.rows, Prefs.maskFor(g.cols, g.rows))
        gridInfo.text = getString(R.string.grid_info, g.cols, g.rows, g.outW, g.outH, g.fps, Prefs.bitrateKbps)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        engine?.setPreviewSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine?.setPreviewSurface(null)
    }

    // ------------------------------------------------------------ события движка
    override fun onAnalysis(cols: Int, rows: Int, active: BooleanArray, motion: Boolean, recording: Boolean) {
        if (overlay.cols == cols && overlay.rows == rows) {
            overlay.active = active
            overlay.recording = recording
            overlay.invalidate()
        }
        val running = CameraService.active
        val (txt, color) = when {
            recording -> getString(R.string.st_recording) to Theme.DANGER
            CameraService.sleeping -> getString(R.string.st_night, Fmt.hm(Prefs.schedStartMin)) to Theme.MUTED
            running -> getString(R.string.st_watching) to Theme.ACCENT
            else -> getString(R.string.st_preview) to Theme.MUTED
        }
        status.text = txt
        status.setTextColor(color)
    }

    override fun onWarning(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Только для превью: телефон повернули в руках, пока прикидывают крепление — обновляем рамку и сетку сразу. */
    override fun onGeometryChanged(g: Geometry) {
        applyGeometry(g)
    }

    // ------------------------------------------------------------ кнопки
    private fun updateUi() {
        val running = CameraService.active
        startBtn.text = getString(if (starting) R.string.btn_starting else if (running) R.string.btn_stop else R.string.btn_start)
        val bg = if (running) Theme.DANGER else Theme.ACCENT
        startBtn.background = roundBg(bg, 14, this)
        startBtn.setTextColor(if (running) Color.WHITE else Color.parseColor("#06210F"))
        hint.text = if (running)
            getString(R.string.hint_running)
        else
            getString(R.string.hint_idle)
        if (running) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!hasCamera()) status.text = getString(R.string.need_camera_perm)
    }

    private fun toggleMaskMode() {
        overlay.editMode = !overlay.editMode
        maskBtn.text = getString(if (overlay.editMode) R.string.mask_on else R.string.mask_off)
        maskBtn.background = roundBg(if (overlay.editMode) Theme.ACCENT else Theme.CARD2, 14, this)
        maskBtn.setTextColor(if (overlay.editMode) Color.parseColor("#06210F") else Theme.TEXT)
    }

    private fun clearMask() {
        val e = engine ?: return
        val empty = BooleanArray(e.geometry.cols * e.geometry.rows)
        Prefs.saveMask(e.geometry.cols, e.geometry.rows, empty)
        overlay.setGrid(e.geometry.cols, e.geometry.rows, empty)
        e.reloadConfig()
    }

    private fun onStartStop() {
        if (starting) return
        if (CameraService.active) {
            CameraService.stop(this)
            main.postDelayed({
                val e = engine
                if (e == null || e.stopped || !ownsEngine) { // сервис уже мог позвать onStopped
                    detach()
                    attach()
                }
                updateUi()
            }, 300)
            return
        }
        if (!hasCamera()) {
            requestPermissionsIfNeeded()
            return
        }
        preflight { doStart() }
    }

    private fun doStart() {
        starting = true
        detach() // освобождаем камеру от превью, дальше она достанется сервису
        updateUi()
        CameraService.start(this)
        waitForService(0)
    }

    private fun waitForService(n: Int) {
        if (CameraService.active) {
            starting = false
            attach()
            updateUi()
            return
        }
        if (n < 60) {
            main.postDelayed({ waitForService(n + 1) }, 100)
        } else {
            starting = false
            Toast.makeText(this, getString(R.string.start_failed), Toast.LENGTH_LONG).show()
            attach()
            updateUi()
        }
    }

    // ------------------------------------------------------------ подсказки при первом запуске
    private fun preflight(done: () -> Unit) {
        val steps = ArrayList<((() -> Unit)) -> Unit>()
        if (!Prefs.pinAdviceShown) {
            steps.add { next ->
                Prefs.pinAdviceShown = true
                info(
                    getString(R.string.dlg_pin_title),
                    getString(R.string.dlg_pin_msg), next
                )
            }
        }
        if (Prefs.codecMode == "auto" && !Codecs.hevcAvailable() && !Prefs.codecPopupShown) {
            steps.add { next ->
                Prefs.codecPopupShown = true
                info(
                    getString(R.string.dlg_codec_title),
                    getString(R.string.dlg_codec_msg), next
                )
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!Prefs.batteryAsked && !pm.isIgnoringBatteryOptimizations(packageName)) {
            steps.add { next ->
                Prefs.batteryAsked = true
                info(
                    getString(R.string.dlg_battery_title),
                    getString(R.string.dlg_battery_msg), {
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:$packageName"))
                            )
                        } catch (e: Exception) { logE("battery dialog", e) }
                        next()
                    }
                )
            }
        }
        runSteps(steps, 0, done)
    }

    private fun runSteps(steps: List<((() -> Unit)) -> Unit>, i: Int, done: () -> Unit) {
        if (i >= steps.size) done() else steps[i] { runSteps(steps, i + 1, done) }
    }
}
