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
    const val ACTION_INSTALL_STATUS = "com.intuitivesolns.bndraft.INSTALL_STATUS"

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

    /**
     * Hands the APK to PackageInstaller. The result (including the "Update?" confirmation
     * Android asks for) comes back to [statusTarget] as an ACTION_INSTALL_STATUS intent.
     */
    fun install(context: Context, apk: File, statusTarget: Class<*>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, statusTarget)
                .setAction(ACTION_INSTALL_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Mutable: the installer adds the status extras to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getActivity(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
