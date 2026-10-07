package com.camerawatcher

import android.app.Activity
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.widget.Switch
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import java.io.File

class SettingsActivity : Activity() {

    private lateinit var col: LinearLayout
    private val bitrates = listOf(256, 512, 768, 1000, 1500, 2000, 3000, 4000, 6000, 8000, 12000)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Loc.ctx(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroll.addView(col)
        setContentView(scroll)
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(text(getString(R.string.settings_title), 22f, Theme.TEXT, true))
        if (CameraService.active) {
            col.addView(text(getString(R.string.settings_running_note), 12f, Theme.MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        languageCard()
        sourceCard()
        recordingCard()
        detectionCard()
        scheduleCard()
        timelapseCard()
        driveCard()
        storageCard()
    }

    private fun sourceCard() {
        val c = section(getString(R.string.sec_source))
        val isUsb = Prefs.cameraSource == "usb"
        c.addView(row(getString(R.string.camera_source), getString(if (isUsb) R.string.source_usb else R.string.source_phone)) {
            pickList(getString(R.string.camera_source), listOf(getString(R.string.source_phone), getString(R.string.source_usb)), if (isUsb) 1 else 0) { i ->
                Prefs.cameraSource = if (i == 1) "usb" else "phone"
                render()
            }
        })
        c.addView(text(if (isUsb) getString(R.string.source_usb_note) else getString(R.string.source_phone_note), 12f, Theme.MUTED))
        if (CameraService.active) {
            c.addView(text(getString(R.string.source_change_note), 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun aspectName(s: Size): String {
        val r = s.width.toFloat() / s.height
        return when {
            Math.abs(r - 16f / 9f) < 0.02f -> "16:9"
            Math.abs(r - 4f / 3f) < 0.02f -> "4:3"
            else -> "%.2f".format(r)
        }
    }

    private fun lenLabel(sec: Int): String =
        if (sec < 60) getString(R.string.sec_fmt, sec) else getString(R.string.min_fmt, sec / 60)

    private fun languageCard() {
        val c = section(getString(R.string.sec_language))
        val labels = listOf(getString(R.string.lang_auto), "English", "Русский", "Українська")
        val idx = Loc.CODES.indexOf(Prefs.lang).coerceAtLeast(0)
        c.addView(row(getString(R.string.lang_row), labels[idx]) {
            pickList(getString(R.string.lang_row), labels, idx) { i ->
                Prefs.lang = Loc.CODES[i]
                recreate()
            }
        })
    }

    private fun section(title: String): LinearLayout {
        val c = card()
        c.addView(text(title, 13f, Theme.MUTED, true).apply { setPadding(0, 0, 0, dp(4)) })
        col.addView(c)
        return c
    }

    // ------------------------------------------------------------ запись
    private fun recordingCard() {
        if (Prefs.cameraSource == "usb") { usbRecordingCard(); return }
        val c = section(getString(R.string.sec_recording))
        val ci = try { CameraInfo.get(this) } catch (e: Exception) { null }
        if (ci == null) {
            c.addView(text(getString(R.string.camera_unavailable_short), 14f, Theme.DANGER))
            return
        }
        val size = ci.sizes.minByOrNull { Math.abs(it.width * it.height - Prefs.resW * Prefs.resH) } ?: Size(Prefs.resW, Prefs.resH)

        c.addView(row(getString(R.string.resolution), "${size.width}×${size.height}") {
            val items = ci.sizes.map { "${it.width}×${it.height}  (${aspectName(it)})" }
            pickList(getString(R.string.resolution), items, ci.sizes.indexOf(size)) { i ->
                Prefs.resW = ci.sizes[i].width
                Prefs.resH = ci.sizes[i].height
                render()
            }
        })

        c.addView(row(getString(R.string.fps_row), "${Prefs.fps}") {
            val std = CameraInfo.STANDARD_FPS
            val items = std.map { getString(if (ci.fpsSupported(size, it)) R.string.fps_item else R.string.fps_item_unavail, it) }
            pickList(getString(R.string.fps_dialog_title, size.width, size.height), items, std.indexOf(Prefs.fps)) { i ->
                if (ci.fpsSupported(size, std[i])) {
                    Prefs.fps = std[i]
                } else {
                    Toast.makeText(this, getString(R.string.fps_unsupported, std[i]), Toast.LENGTH_LONG).show()
                }
                render()
            }
        })

        bitrateRow(c)
        codecRow(c)
        labelRow(c)
        c.addView(text(getString(R.string.rotation_note), 12f, Theme.MUTED))
        postrollRow(c)
    }

    /** То же самое для веб-камеры: разрешение — фиксированный список (реальные размеры узнаются только
     * после подключения конкретной камеры), FPS не выбирается (библиотека не даёт на это влиять),
     * поворот — вручную, камера крепится отдельно от телефона. */
    private fun usbRecordingCard() {
        val c = section(getString(R.string.sec_recording))
        c.addView(text(getString(R.string.usb_card_hint), 12f, Theme.MUTED).apply { setPadding(0, 0, 0, dp(6)) })

        val sizes = listOf(640 to 480, 1280 to 720, 1920 to 1080)
        val cur = sizes.indexOf(Prefs.usbResW to Prefs.usbResH).coerceAtLeast(0)
        c.addView(row(getString(R.string.resolution), "${Prefs.usbResW}×${Prefs.usbResH}") {
            val items = sizes.map { "${it.first}×${it.second}" }
            pickList(getString(R.string.resolution), items, cur) { i ->
                Prefs.usbResW = sizes[i].first
                Prefs.usbResH = sizes[i].second
                render()
            }
        })
        c.addView(text(getString(R.string.usb_resolution_note), 12f, Theme.MUTED))

        bitrateRow(c)
        codecRow(c)
        labelRow(c)

        val rotVals = listOf(0, 90, 180, 270)
        val rotItems = rotVals.map { getString(R.string.deg_fmt, it) }
        c.addView(row(getString(R.string.usb_rotation), getString(R.string.deg_fmt, Prefs.usbRotation)) {
            pickList(getString(R.string.usb_rotation), rotItems, rotVals.indexOf(Prefs.usbRotation).coerceAtLeast(0)) { i ->
                Prefs.usbRotation = rotVals[i]
                render()
            }
        })
        c.addView(text(getString(R.string.usb_rotation_note), 12f, Theme.MUTED))
        postrollRow(c)
    }

    private fun bitrateRow(c: LinearLayout) {
        c.addView(row(getString(R.string.bitrate), getString(R.string.kbps_fmt, Prefs.bitrateKbps)) {
            pickList(getString(R.string.bitrate), bitrates.map { getString(R.string.kbps_fmt, it) }, bitrates.indexOf(Prefs.bitrateKbps)) { i ->
                Prefs.bitrateKbps = bitrates[i]
                render()
            }
        })
        val audio = if (Prefs.mute) 0 else 64
        val mbPerHour = (Prefs.bitrateKbps + audio) * 1000L / 8 * 3600 / 1_000_000
        c.addView(text(getString(R.string.bitrate_note, mbPerHour), 12f, Theme.MUTED))
    }

    private fun codecRow(c: LinearLayout) {
        val codecLabel = when {
            Prefs.codecMode == "h264" -> "H.264"
            Codecs.hevcAvailable() && !Prefs.hevcFailed -> getString(R.string.codec_auto_hevc)
            else -> getString(R.string.codec_auto_h264)
        }
        c.addView(row(getString(R.string.codec), codecLabel) {
            pickList(getString(R.string.codec), listOf(getString(R.string.codec_opt_auto), getString(R.string.codec_opt_h264)), if (Prefs.codecMode == "auto") 0 else 1) { i ->
                Prefs.codecMode = if (i == 0) "auto" else "h264"
                if (i == 0) Prefs.hevcFailed = false
                render()
            }
        })
    }

    private fun labelRow(c: LinearLayout) {
        c.addView(row(getString(R.string.label_row), if (Prefs.label.isBlank()) getString(R.string.label_only_datetime) else Prefs.label) {
            editText(getString(R.string.label_dialog), Prefs.label) { Prefs.label = it; render() }
        })
    }

    private fun postrollRow(c: LinearLayout) {
        c.addView(row(getString(R.string.postroll), getString(R.string.sec_fmt, Prefs.postRollSec)) {
            pickNumber(getString(R.string.postroll_dialog), 2, 60, Prefs.postRollSec) { Prefs.postRollSec = it; render() }
        })
    }

    // ------------------------------------------------------------ детекция
    private fun detectionCard() {
        val c = section(getString(R.string.sec_detection))
        c.addView(row(getString(R.string.grid_long), "${Prefs.gridLong}") {
            pickNumber(getString(R.string.grid_long_dialog), 4, 32, Prefs.gridLong) { Prefs.gridLong = it; render() }
        })
        c.addView(row(getString(R.string.grid_short), "${Prefs.gridShort}") {
            pickNumber(getString(R.string.grid_short_dialog), 3, 32, Prefs.gridShort) { Prefs.gridShort = it; render() }
        })
        c.addView(text(getString(R.string.grid_note), 12f, Theme.MUTED))

        val sw = Switch(this).apply {
            text = getString(R.string.sound_alert)
            setTextColor(Theme.TEXT)
            textSize = 15f
            isChecked = Prefs.soundAlert
            setOnCheckedChangeListener { _, on -> Prefs.soundAlert = on; render() }
        }
        sw.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }
        c.addView(sw)
        if (Prefs.soundAlert) {
            c.addView(row(getString(R.string.sound_alert_cooldown), getString(R.string.sec_fmt, Prefs.soundAlertCooldownSec)) {
                pickNumber(getString(R.string.sound_alert_cooldown_dialog), 1, 60, Prefs.soundAlertCooldownSec) { Prefs.soundAlertCooldownSec = it; render() }
            })
        }
        c.addView(text(getString(R.string.sound_alert_note), 12f, Theme.MUTED))
    }

    // ------------------------------------------------------------ расписание записи
    private fun scheduleCard() {
        val c = section(getString(R.string.sec_schedule))
        val sw = Switch(this).apply {
            text = getString(R.string.sched_switch)
            setTextColor(Theme.TEXT)
            textSize = 15f
            isChecked = Prefs.schedEnabled
            setOnCheckedChangeListener { _, on ->
                Prefs.schedEnabled = on
                CameraService.pokeSchedule()
                render()
            }
        }
        c.addView(sw)
        if (Prefs.schedEnabled) {
            c.addView(row(getString(R.string.sched_on), Fmt.hm(Prefs.schedStartMin)) {
                TimePickerDialog(this, { _, h, m -> Prefs.schedStartMin = h * 60 + m; CameraService.pokeSchedule(); render() },
                    Prefs.schedStartMin / 60, Prefs.schedStartMin % 60, true).show()
            })
            c.addView(row(getString(R.string.sched_off), Fmt.hm(Prefs.schedEndMin)) {
                TimePickerDialog(this, { _, h, m -> Prefs.schedEndMin = h * 60 + m; CameraService.pokeSchedule(); render() },
                    Prefs.schedEndMin / 60, Prefs.schedEndMin % 60, true).show()
            })
            c.addView(text(
                if (Prefs.scheduleAllowsNow()) getString(R.string.sched_now_allowed) else getString(R.string.sched_now_off),
                13f, if (Prefs.scheduleAllowsNow()) Theme.ACCENT else Theme.MUTED, true
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        c.addView(text(
            getString(R.string.sched_note), 12f, Theme.MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
    }

    // ------------------------------------------------------------ таймлапс
    private val tlLengths = listOf(15, 30, 60, 120, 180, 300, 600)

    private fun timelapseCard() {
        val c = section(getString(R.string.sec_timelapse))
        val sw = Switch(this).apply {
            text = getString(R.string.tl_whole_day)
            setTextColor(Theme.TEXT)
            textSize = 15f
            isChecked = Prefs.tlWholeDay
            setOnCheckedChangeListener { _, on -> Prefs.tlWholeDay = on; render() }
        }
        c.addView(sw)
        if (!Prefs.tlWholeDay) {
            c.addView(row(getString(R.string.tl_from), Fmt.hm(Prefs.tlStartMin)) {
                TimePickerDialog(this, { _, h, m -> Prefs.tlStartMin = h * 60 + m; render() },
                    Prefs.tlStartMin / 60, Prefs.tlStartMin % 60, true).show()
            })
            c.addView(row(getString(R.string.tl_to), Fmt.hm(Prefs.tlEndMin)) {
                TimePickerDialog(this, { _, h, m -> Prefs.tlEndMin = h * 60 + m; render() },
                    Prefs.tlEndMin / 60, Prefs.tlEndMin % 60, true).show()
            })
        } else {
            c.addView(text(getString(R.string.tl_whole_note), 12f, Theme.MUTED))
        }
        c.addView(row(getString(R.string.tl_length), lenLabel(Prefs.tlLengthSec)) {
            val labels = tlLengths.map { lenLabel(it) }
            pickList(getString(R.string.tl_length_dialog), labels, tlLengths.indexOf(Prefs.tlLengthSec).coerceAtLeast(0)) { i ->
                Prefs.tlLengthSec = tlLengths[i]
                render()
            }
        })
        c.addView(text(getString(R.string.tl_note), 12f, Theme.MUTED))
        c.addView(button(getString(R.string.tl_build_now), Theme.CARD2, Theme.TEXT) {
            Toast.makeText(this, getString(R.string.tl_building), Toast.LENGTH_SHORT).show()
            val ctx = applicationContext
            Thread {
                val hk = Housekeeper(ctx)
                val ok = try { hk.buildTimelapse(Fmt.date(System.currentTimeMillis()), force = true, kickAfter = false) } catch (e: Exception) { logE("tl", e); false }
                hk.stop()
                runOnUiThread {
                    Toast.makeText(this, if (ok) getString(R.string.tl_ready) else getString(R.string.tl_no_clips), Toast.LENGTH_LONG).show()
                }
            }.start()
        })
    }

    // ------------------------------------------------------------ Google Drive
    private val REQ_DRIVE = 4711

    private fun driveCard() {
        val c = section(getString(R.string.sec_drive))
        val status = when {
            Prefs.driveNeedsLogin -> getString(R.string.drive_relogin_needed)
            Drive.isConfigured() -> if (Prefs.driveEmail.isNotEmpty()) Prefs.driveEmail else getString(R.string.drive_connected)
            else -> getString(R.string.drive_not_connected)
        }
        c.addView(row(getString(R.string.drive_account), status, null))
        if (Drive.isConfigured()) {
            if (Prefs.driveNeedsLogin) c.addView(button(getString(R.string.btn_relogin)) { signIn() })
            c.addView(button(getString(R.string.btn_signout), Theme.CARD2, Theme.TEXT) { Drive.signOut(); render() })
        } else {
            c.addView(button(getString(R.string.btn_signin)) { signIn() })
        }
        c.addView(text(getString(R.string.drive_note, Drive.rootName()), 12f, Theme.MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })

        // Данные для Google Cloud Console (нужны один раз, чтобы Google узнал приложение)
        val sha = Drive.signingSha1(this)
        c.addView(text(getString(R.string.drive_help), 12f, Theme.MUTED).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        c.addView(row(getString(R.string.package_name), packageName) { copy(getString(R.string.package_name), packageName) })
        c.addView(row("SHA-1", sha) { copy("SHA-1", sha) })
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(R.string.copied_fmt, label), Toast.LENGTH_SHORT).show()
    }

    private fun signIn() {
        Drive.client(this).authorize(Drive.authRequest(true))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    try {
                        startIntentSenderForResult(pi.intentSender, REQ_DRIVE, null, 0, 0, 0)
                    } catch (e: IntentSender.SendIntentException) {
                        info(getString(R.string.signin_failed), e.message ?: getString(R.string.unknown_error))
                    }
                } else {
                    finishSignIn(r.accessToken)
                }
            }
            .addOnFailureListener { e -> info(getString(R.string.signin_failed), explain(e)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DRIVE) return
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, getString(R.string.signin_cancelled), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            finishSignIn(Drive.client(this).getAuthorizationResultFromIntent(data).accessToken)
        } catch (e: ApiException) {
            info(getString(R.string.signin_failed), explain(e))
        }
    }

    private fun finishSignIn(token: String?) {
        Drive.onSignedIn(token)
        Toast.makeText(this, getString(R.string.drive_connected_toast), Toast.LENGTH_LONG).show()
        render()
        Thread {
            val mail = try { Drive.fetchEmail() } catch (e: Exception) { logE("email", e); null }
            if (mail != null) {
                Prefs.driveEmail = mail
                runOnUiThread { render() }
            }
        }.start()
    }

    private fun explain(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> getString(R.string.err_10)
            7 -> getString(R.string.err_7)
            else -> (e.message ?: getString(R.string.err_unknown)) + if (code != null) getString(R.string.err_code_fmt, code) else ""
        }
    }

    // ------------------------------------------------------------ хранилище
    private fun storageCard() {
        val c = section(getString(R.string.sec_storage))
        val free = Storage.freeBytes(this) / (1024 * 1024)
        val dir = Storage.root(this)
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

        c.addView(text(getString(R.string.st_clips_title), 13f, Theme.MUTED, true).apply { setPadding(0, dp(6), 0, 0) })
        c.addView(row(getString(R.string.keep_drive), getString(R.string.days_fmt, Prefs.driveKeepDaysClips)) {
            pickNumber(getString(R.string.keep_clips_drive_dlg), 1, 365, Prefs.driveKeepDaysClips) { Prefs.driveKeepDaysClips = it; render() }
        })
        c.addView(row(getString(R.string.keep_phone), getString(R.string.days_fmt, Prefs.localKeepDaysClips)) {
            pickNumber(getString(R.string.keep_clips_phone_dlg), 1, 30, Prefs.localKeepDaysClips) { Prefs.localKeepDaysClips = it; render() }
        })
        val mbPerHour = (Prefs.bitrateKbps + if (Prefs.mute) 0 else 64) * 1000L / 8 * 3600 / 1_000_000
        val gbClips = mbPerHour * 2.0 * Prefs.driveKeepDaysClips / 1000.0
        c.addView(text(
            getString(R.string.storage_estimate, Prefs.driveKeepDaysClips, "%.1f".format(gbClips)),
            12f, Theme.MUTED
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        c.addView(text(getString(R.string.st_tl_title), 13f, Theme.MUTED, true))
        c.addView(row(getString(R.string.keep_drive), getString(R.string.days_fmt, Prefs.driveKeepDaysTimelapse)) {
            pickNumber(getString(R.string.keep_tl_drive_dlg), 1, 3650, Prefs.driveKeepDaysTimelapse) { Prefs.driveKeepDaysTimelapse = it; render() }
        })
        c.addView(row(getString(R.string.keep_phone), getString(R.string.days_fmt, Prefs.localKeepDaysTimelapse)) {
            pickNumber(getString(R.string.keep_tl_phone_dlg), 1, 365, Prefs.localKeepDaysTimelapse) { Prefs.localKeepDaysTimelapse = it; render() }
        })
        c.addView(text(getString(R.string.tl_size_note), 12f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        c.addView(text(getString(R.string.storage_note), 12f, Theme.MUTED))
        c.addView(row(getString(R.string.free_phone), getString(R.string.mb_fmt, free), null))
        c.addView(row(getString(R.string.used_rec), getString(R.string.mb_fmt, used), null))
        c.addView(text(getString(R.string.low_space_note, dir.path), 12f, Theme.MUTED))
    }

}
