package com.camerawatcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DriveAuthException(msg: String) : Exception(msg)
class DriveFullException : Exception("Google Drive is full")

/**
 * Загрузка на Google Drive без Google API-клиента: обычный REST + токен от Google Play Services.
 *
 * Вход: AuthorizationClient (Google Identity Services). Пользователь один раз выбирает аккаунт и нажимает «Разрешить»;
 * дальше токены выдаёт сам Play Services (без окон и без хранения паролей/секретов в приложении).
 * Доступ только к файлам, созданным самим приложением (scope drive.file).
 */
object Drive {
    /** Имя корневой папки на Drive (язык фиксируется при входе, см. DriveNames). */
    fun rootName(): String = DriveNames.root(Prefs.driveNamesLang)
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    private lateinit var appCtx: Context
    private var cachedToken = ""
    private var cachedUntil = 0L

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun isConfigured() = Prefs.driveSignedIn

    // ---------------- вход через Play Services ----------------
    fun client(ctx: Context): AuthorizationClient = Identity.getAuthorizationClient(ctx)

    fun authRequest(selectAccount: Boolean): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (selectAccount) b.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        return b.build()
    }

    /** Вызывается после успешного входа (из экрана настроек). */
    fun onSignedIn(token: String?) {
        cachedToken = token ?: ""
        cachedUntil = System.currentTimeMillis() + 45 * 60_000L
        Prefs.driveSignedIn = true
        Prefs.driveNamesLang = Loc.effective(appCtx) // язык имён папок фиксируем при входе
        Prefs.driveNeedsLogin = false
        Prefs.clearDriveFolders()
        Prefs.lowDrive = false
    }

    fun signOut() {
        val t = cachedToken
        cachedToken = ""
        cachedUntil = 0
        Prefs.driveSignedIn = false
        Prefs.driveNeedsLogin = false
        Prefs.driveEmail = ""
        Prefs.clearDriveFolders()
        Prefs.lowDrive = false
        if (t.isNotEmpty()) {
            try { client(appCtx).clearToken(ClearTokenRequest.builder().setToken(t).build()) } catch (_: Exception) {}
        }
    }

    /** SHA-1 подписи приложения — его нужно один раз указать в Google Cloud Console. */
    @Suppress("DEPRECATION")
    fun signingSha1(ctx: Context): String = try {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        if (sig == null) Loc.ctx(ctx).getString(R.string.sha_unknown)
        else MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) { Loc.ctx(ctx).getString(R.string.sha_unknown) }

    /** Токен доступа. Только из фонового потока (ждёт ответа Play Services). */
    @Synchronized
    private fun accessToken(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && cachedToken.isNotEmpty() && now < cachedUntil) return cachedToken
        if (force && cachedToken.isNotEmpty()) {
            try {
                Tasks.await(client(appCtx).clearToken(ClearTokenRequest.builder().setToken(cachedToken).build()), 10, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            cachedToken = ""
        }
        val result = try {
            Tasks.await(client(appCtx).authorize(authRequest(false)), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw DriveAuthException("Play Services did not grant Drive access: ${e.message}")
        }
        if (result.hasResolution()) { // доступ отозван или сменился аккаунт — без участия пользователя не обойтись
            Prefs.driveNeedsLogin = true
            throw DriveAuthException("Sign-in to Google Drive is required again")
        }
        val t = result.accessToken ?: throw DriveAuthException("Play Services returned no access token")
        cachedToken = t
        cachedUntil = now + 45 * 60_000L
        Prefs.driveNeedsLogin = false
        return t
    }

    // ---------------- HTTP ----------------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, url: String, token: String, json: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    /** Запрос к API с одним повтором при протухшем токене. */
    private fun api(method: String, url: String, json: String? = null): Pair<Int, String> {
        var r = request(method, url, accessToken(), json)
        if (r.first == 401) r = request(method, url, accessToken(force = true), json)
        if (r.first == 403 && r.second.contains("storageQuotaExceeded")) throw DriveFullException()
        return r
    }

    /** Почта аккаунта, в который идёт загрузка (для показа в настройках). */
    fun fetchEmail(): String? {
        val (code, text) = api("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
        if (code != 200) return null
        return JSONObject(text).optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
    }

    // ---------------- папки ----------------
    private fun findFolder(name: String, parent: String): String? {
        val q = "name='${name.replace("'", "\\'")}' and '$parent' in parents and " +
            "mimeType='application/vnd.google-apps.folder' and trashed=false"
        val (code, text) = api("GET", "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=files(id)&pageSize=1")
        if (code != 200) throw Exception("Drive: folder lookup failed ($code)")
        val arr = JSONObject(text).getJSONArray("files")
        return if (arr.length() > 0) arr.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(name: String, parent: String): String {
        val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", JSONArray().put(parent)).toString()
        val (code, text) = api("POST", "https://www.googleapis.com/drive/v3/files?fields=id", json = body)
        if (code != 200) throw Exception("Drive: folder creation failed ($code)")
        return JSONObject(text).getString("id")
    }

    /** Гарантирует цепочку папок Root/дата/время-суток и возвращает id последней. Ids кешируются. */
    fun ensurePath(parts: List<String>): String {
        var parent = "root"
        var path = ""
        for (p in parts) {
            path += "/$p"
            var id = Prefs.driveFolder(path)
            if (id == null) {
                id = findFolder(p, parent) ?: createFolder(p, parent)
                Prefs.putDriveFolder(path, id)
            }
            parent = id
        }
        return parent
    }

    // ---------------- загрузка ----------------
    /** Загружает файл потоком (не читает целиком в память). Если кеш папки устарел — сбрасывает и пробует ещё раз. */
    fun upload(file: File, parts: List<String>) {
        for (attempt in 0..1) {
            val parentId = ensurePath(parts)
            val code = uploadOnce(file, parentId)
            if (code in 200..299) return
            if (code == 404 && attempt == 0) { // папку удалили вручную
                Prefs.clearDriveFolders()
                continue
            }
            throw Exception("Drive: upload of ${file.name} failed ($code)")
        }
    }

    private fun uploadOnce(file: File, parentId: String): Int {
        var token = accessToken()
        for (attempt in 0..1) {
            val boundary = "cw" + System.nanoTime()
            val meta = JSONObject().put("name", file.name).put("parents", JSONArray().put(parentId)).toString()
            val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: video/mp4\r\n\r\n").toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
            val c = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setChunkedStreamingMode(256 * 1024)
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            try {
                c.outputStream.use { o ->
                    o.write(head)
                    file.inputStream().use { it.copyTo(o, 64 * 1024) }
                    o.write(tail)
                }
                val code = c.responseCode
                val err = if (code >= 400) c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" else ""
                if (code == 401 && attempt == 0) {
                    token = accessToken(force = true)
                    continue
                }
                if (code == 403 && err.contains("storageQuotaExceeded")) throw DriveFullException()
                return code
            } finally {
                c.disconnect()
            }
        }
        return 401
    }

    // ---------------- обслуживание ----------------
    /** Свободное место на Drive в байтах (null — безлимит или неизвестно). */
    fun freeBytes(): Long? {
        val (code, text) = api("GET", "https://www.googleapis.com/drive/v3/about?fields=storageQuota")
        if (code != 200) return null
        val q = JSONObject(text).optJSONObject("storageQuota") ?: return null
        val limit = q.optString("limit", "").toLongOrNull() ?: return null
        val usage = q.optString("usage", "0").toLongOrNull() ?: 0L
        return limit - usage
    }

    private data class Child(val id: String, val name: String, val folder: Boolean)

    private fun listChildren(parentId: String): List<Child> {
        val out = ArrayList<Child>()
        var pageToken: String? = null
        do {
            val q = "'$parentId' in parents and trashed=false"
            var url = "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=nextPageToken,files(id,name,mimeType)&pageSize=200"
            if (pageToken != null) url += "&pageToken=${enc(pageToken)}"
            val (code, text) = api("GET", url)
            if (code != 200) break
            val o = JSONObject(text)
            val arr = o.getJSONArray("files")
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out.add(Child(f.getString("id"), f.getString("name"), f.getString("mimeType") == "application/vnd.google-apps.folder"))
            }
            pageToken = o.optString("nextPageToken", "").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    private fun deleteFile(id: String) {
        api("DELETE", "https://www.googleapis.com/drive/v3/files/$id")
    }

    private fun deleteFolderRecursive(id: String) {
        for (ch in listChildren(id)) {
            if (ch.folder) deleteFolderRecursive(ch.id) else deleteFile(ch.id)
        }
        deleteFile(id)
    }

    /**
     * Удаляет старое отдельно для клипов-исходников и для таймлапсов (у них разный срок хранения).
     * Структура: <ROOT>/<дата>/<утро|день|вечер|ночь>/клип.mp4 и <ROOT>/<дата>/timelapse_<дата>.mp4.
     */
    fun deleteOld(keepDaysClips: Int, keepDaysTimelapse: Int) {
        val rootId = findFolder(rootName(), "root") ?: return
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = System.currentTimeMillis()
        var touched = false
        for (dateFolder in listChildren(rootId).filter { it.folder }) {
            val t = try { fmt.parse(dateFolder.name)?.time } catch (e: Exception) { null } ?: continue
            val ageDays = (now - t) / (24.0 * 3600 * 1000)
            val clipsExpired = ageDays > keepDaysClips
            val tlExpired = ageDays > keepDaysTimelapse
            if (!clipsExpired && !tlExpired) continue
            val kids = listChildren(dateFolder.id)
            if (clipsExpired) {
                for (ch in kids.filter { it.folder }) { // папки утро/день/вечер/ночь — это клипы
                    deleteFolderRecursive(ch.id)
                    touched = true
                }
            }
            if (tlExpired) {
                for (ch in kids.filter { !it.folder && it.name.startsWith("timelapse_") }) {
                    deleteFile(ch.id)
                    touched = true
                }
            }
            if (listChildren(dateFolder.id).isEmpty()) {
                deleteFile(dateFolder.id)
                touched = true
            }
        }
        if (touched) Prefs.clearDriveFolders() // кеш мог указывать на удалённое
    }
}
