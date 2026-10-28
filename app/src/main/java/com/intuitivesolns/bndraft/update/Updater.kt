package com.intuitivesolns.bndraft.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.intuitivesolns.bndraft.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val versionCode: Int, val versionName: String, val notes: String)

/**
 * Self-update against the newest GitHub release, which carries two assets:
 * version.json ({versionCode, versionName, notes}) and BN-Draft.apk.
 * All calls block; run them off the main thread.
 */
object Updater {
    private fun open(path: String): HttpURLConnection =
        (URL("${BuildConfig.UPDATE_BASE_URL}/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
        }

    /** Returns the latest release if it is newer than what's installed, else null. */
    fun checkForUpdate(): Release? {
        val conn = open("version.json")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val release = Release(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                notes = json.optString("notes"),
            )
            return release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK to the cache dir, reporting progress as 0..100. */
    fun download(context: Context, onProgress: (Int) -> Unit): File {
        val target = File(context.cacheDir, "update.apk")
        val conn = open("BN-Draft.apk")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return target
    }

    /** Install failure reported by [InstallResultReceiver], kept until the UI shows it. */
    fun reportFailure(context: Context, message: String) {
        prefs(context).edit().putString(KEY_FAILURE, message).remove(KEY_INSTALLING).apply()
        failureListener?.invoke()
    }

    /**
     * Remembers that [release] has been handed to the installer. The install can take a
     * while to land (security scans), and the old version may be reopened meanwhile.
     */
    fun markInstalling(context: Context, release: Release) {
        prefs(context).edit()
            .putString(KEY_INSTALLING, "${release.versionCode}|${release.versionName}|${System.currentTimeMillis()}")
            .apply()
    }

    /** The versionName still being installed, or null once it landed, failed or went stale. */
    fun installingVersion(context: Context): String? {
        val p = prefs(context)
        val (code, name, startedAt) = p.getString(KEY_INSTALLING, null)?.split("|") ?: return null
        val pending = code.toInt() > BuildConfig.VERSION_CODE &&
            System.currentTimeMillis() - startedAt.toLong() < INSTALL_TIMEOUT_MS
        if (!pending) p.edit().remove(KEY_INSTALLING).apply()
        return name.takeIf { pending }
    }

    /** Returns and clears the pending install failure, if any. */
    fun takeFailure(context: Context): String? {
        val p = prefs(context)
        return p.getString(KEY_FAILURE, null)?.also { p.edit().remove(KEY_FAILURE).apply() }
    }

    /** Set by the visible activity so it can show failures immediately. */
    @Volatile var failureListener: (() -> Unit)? = null

    private const val KEY_FAILURE = "install_failure"
    private const val KEY_INSTALLING = "installing"
    private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /**
     * Hands the APK to PackageInstaller. The result (including the "Update?" confirmation
     * Android asks for) comes back to [InstallResultReceiver].
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            // Mutable: the installer adds the status extras to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
