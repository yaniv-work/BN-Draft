package com.intuitivesolns.bndraft

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInstaller
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = TextView(this).apply {
            text = "BN-Draft v${BuildConfig.VERSION_NAME}"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        val build = TextView(this).apply {
            text = "build ${BuildConfig.VERSION_CODE}"
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
            addView(build)
            addView(status)
            addView(checkButton)
        })

        if (savedInstanceState == null) checkForUpdate(manual = false)
        handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    private fun checkForUpdate(manual: Boolean) {
        checkButton.isEnabled = false
        if (manual) status.text = "Checking…"
        thread {
            val result = runCatching { Updater.checkForUpdate() }
            runOnUiThread {
                checkButton.isEnabled = true
                status.text = ""
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
            .setPositiveButton("Update") { _, _ -> startUpdate() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate() {
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
                Updater.install(this, apk, MainActivity::class.java)
            }.onFailure {
                runOnUiThread {
                    checkButton.isEnabled = true
                    status.text = "Update failed: ${it.message}"
                }
            }
        }
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> status.text = "Updated"
            else -> {
                checkButton.isEnabled = true
                status.text = "Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            }
        }
    }
}
