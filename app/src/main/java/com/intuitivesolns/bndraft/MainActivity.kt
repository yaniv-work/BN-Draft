package com.intuitivesolns.bndraft

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.intuitivesolns.bndraft.update.Release
import com.intuitivesolns.bndraft.update.Updater
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var checkButton: Button
    private var checkOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = TextView(this).apply {
            text = "BN-Draft v${BuildConfig.VERSION_NAME}"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 24)
        }
        checkButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdate(manual = true) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(version)
            addView(status)
            addView(checkButton)
        })

        checkOnResume = savedInstanceState == null
    }

    override fun onResume() {
        super.onResume()
        Updater.failureListener = { runOnUiThread { showPendingFailure() } }
        // A failed or still-landing install takes priority over offering the same update again.
        if (!showPendingFailure() && !showInstalling() && checkOnResume) checkForUpdate(manual = false)
        checkOnResume = false
    }

    override fun onPause() {
        Updater.failureListener = null
        super.onPause()
    }

    private fun showPendingFailure(): Boolean {
        val failure = Updater.takeFailure(this) ?: return false
        checkButton.isEnabled = true
        status.text = "Install failed: $failure"
        AlertDialog.Builder(this)
            .setTitle("Update not installed")
            .setMessage(failure)
            .setPositiveButton("OK", null)
            .show()
        return true
    }

    private fun showInstalling(): Boolean {
        val name = Updater.installingVersion(this) ?: return false
        status.text = "Finishing update to v$name…\nReopen the app once the scan is done."
        return true
    }

    private fun checkForUpdate(manual: Boolean) {
        if (showInstalling()) return
        checkButton.isEnabled = false
        if (manual) status.text = "Checking…"
        thread {
            val result = runCatching { Updater.checkForUpdate() }
            runOnUiThread {
                checkButton.isEnabled = true
                if (manual) status.text = ""
                result.fold(
                    onSuccess = { release ->
                        if (release != null) offerUpdate(release)
                        else if (manual) status.text = "You're up to date"
                    },
                    onFailure = { if (manual) status.text = "Update check failed: ${it.message}" },
                )
            }
        }
    }

    private fun offerUpdate(release: Release) {
        AlertDialog.Builder(this)
            .setTitle("Update to v${release.versionName}?")
            .setMessage(release.notes.ifBlank { "A new version is available." })
            .setPositiveButton("Update") { _, _ -> startUpdate(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(release: Release) {
        // One-time grant: "Install unknown apps" for BN-Draft.
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow BN-Draft to install updates, then tap Check again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        checkButton.isEnabled = false
        thread {
            runCatching {
                val apk = Updater.download(this) { pct -> runOnUiThread { status.text = "Downloading… $pct%" } }
                runOnUiThread { status.text = "Installing…" }
                Updater.install(this, apk)
                Updater.markInstalling(this, release)
            }.onFailure {
                runOnUiThread {
                    checkButton.isEnabled = true
                    status.text = "Update failed: ${it.message}"
                }
            }
        }
    }
}
