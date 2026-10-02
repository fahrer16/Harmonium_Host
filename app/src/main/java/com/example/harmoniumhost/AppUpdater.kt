package com.example.harmoniumhost

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Updates from this project's GitHub releases, shown in Home Assistant as the device's update
 * entity: HA lists the new version and its notes, and Install downloads the APK, checks it
 * against the release's SHA-256, and hands it to Android's installer. Android then asks on the
 * remote to confirm (a normal app can't install silently), so the screen is woken for it.
 * The first time, Android also asks to allow "Install unknown apps" for this app.
 */
class AppUpdater(private val context: Context, private val changed: () -> Unit) {

    private companion object {
        const val TAG = "HarmoniumHost"
        const val REPO = "fahrer16/Harmonium_Host"
        const val CHECK_EVERY_MS = 6 * 3600_000L
        const val FIRST_CHECK_MS = 60_000L
    }

    data class Release(val version: String, val notes: String, val url: String, val apk: String?, val sha256: String?)

    private val app = HostApp.of(context)
    private val main = Handler(Looper.getMainLooper())
    private val periodic = Runnable { check(); schedule(CHECK_EVERY_MS) }
    @Volatile private var latest: Release? = null
    @Volatile private var busy = false
    @Volatile private var progress: Float? = null
    /** Shown in HA in place of the notes when something needs attention. */
    @Volatile private var note = ""

    fun start() = schedule(FIRST_CHECK_MS)

    private fun schedule(delay: Long) {
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, delay)
    }

    fun info(): UpdateInfo {
        val current = app.versionName
        val r = latest
        return UpdateInfo(
            current = current,
            latest = r?.version ?: current,
            title = "Harmonium Host",
            summary = note.ifEmpty { r?.notes ?: "" },
            url = r?.url ?: "https://github.com/$REPO/releases",
            inProgress = busy,
            progress = progress,
        )
    }

    /** From HA: 1 = install, 2 = check now. */
    fun command(c: Int) = when (c) {
        1 -> install()
        2 -> check()
        else -> Unit
    }

    fun check() {
        thread(name = "update-check") {
            try {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()
                val json = app.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}")
                    JSONObject(r.body!!.string())
                }
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                var sha: String? = null
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk")) apk = a.optString("browser_download_url")
                    if (name.endsWith(".apk.sha256")) sha = a.optString("browser_download_url")
                }
                latest = Release(
                    version = json.optString("tag_name").removePrefix("v"),
                    notes = summarize(json.optString("body")),
                    url = json.optString("html_url"),
                    apk = apk, sha256 = sha,
                )
                Log.i(TAG, "update check: latest ${latest?.version}, installed ${app.versionName}")
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e")
            }
            changed()
        }
    }

    /** The release notes' first paragraph, without Markdown, short enough for HA (255 characters). */
    private fun summarize(body: String): String {
        val first = body.trim().split(Regex("\\n\\s*\\n")).firstOrNull() ?: ""
        return first.replace(Regex("[*_`>#]"), "").replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\s+"), " ").trim().take(255)
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun install() {
        val r = latest
        if (busy || r == null || r.apk == null || !newer(r.version, app.versionName)) {
            Log.i(TAG, "update: nothing to install"); return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            note = "Allow \"Install unknown apps\" for Harmonium Host on the remote (it's open there now), then press Install again."
            main.post {
                app.device?.wake()
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            changed(); return
        }
        busy = true
        note = ""
        progress = 0f
        changed()
        thread(name = "update-install") {
            try {
                val file = File(context.cacheDir, "update.apk")
                download(r.apk, file)
                r.sha256?.let { verify(file, it) }
                progress = null
                changed()
                commit(file)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: $e")
                note = "Update failed: ${e.message}"
                busy = false
                progress = null
                changed()
            }
        }
    }

    private fun download(url: String, to: File) {
        app.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("download answered ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            var done = 0L
            var shown = 0
            to.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= shown + 10) { shown = pct; progress = pct.toFloat(); changed() }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "update: downloaded ${to.length() / 1024} KB")
    }

    private fun verify(file: File, shaUrl: String) {
        val expected = app.http.newCall(Request.Builder().url(shaUrl).build()).execute().use { r ->
            r.body!!.string().trim().split(Regex("\\s+")).first().lowercase()
        }
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) throw IllegalStateException("the download doesn't match its SHA-256")
    }

    /** Android's installer takes it from here; [UpdateReceiver] shows its confirmation on the remote. */
    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("harmonium-host.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer adds the status to it. (FLAG_MUTABLE is 0x02000000, API 31+; older Android ignores it.)
            val status = PendingIntent.getBroadcast(context, id,
                Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or 0x02000000)
            session.commit(status.intentSender)
        }
        file.delete()
        Log.i(TAG, "update: handed to the installer")
    }

    /** The installer's answer (from [UpdateReceiver]). */
    fun installerResult(code: Int, message: String?) {
        if (code != PackageInstaller.STATUS_SUCCESS) {
            note = "Not installed: ${message ?: "status $code"}"
            Log.w(TAG, "update: $note")
        }
        busy = false
        changed()
    }
}

/**
 * Two jobs: shows Android's install confirmation when the installer asks for it, and after an
 * update brings the app back up (the update stops the running app).
 */
class UpdateReceiver : BroadcastReceiver() {
    companion object { const val ACTION_STATUS = "com.example.harmoniumhost.UPDATE_STATUS" }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ACTION_STATUS -> {
                val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val device = HostApp.of(context).device
                if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    device?.wake()
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    device?.updater?.installerResult(code, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
        }
    }
}
