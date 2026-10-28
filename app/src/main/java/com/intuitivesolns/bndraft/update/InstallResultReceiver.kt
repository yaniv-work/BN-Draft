package com.intuitivesolns.bndraft.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives PackageInstaller session results. A broadcast (rather than relaunching the
 * activity) avoids stacking a second MainActivity on top of the system install dialog.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // On success the app is replaced and restarted, so only failures need reporting.
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Updater.reportFailure(context, "${statusName(status)}${message?.let { ": $it" } ?: ""}")
        }
    }

    private fun statusName(status: Int) = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Aborted"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflict"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Storage"
        else -> "Failed ($status)"
    }
}
