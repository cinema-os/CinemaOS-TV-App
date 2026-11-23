package com.nuvio.tv.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow

private const val TAG = "UpdateInstall"

/** How a user-started install ended without replacing the app, so the update screen can stop waiting. */
internal sealed interface UpdateInstallOutcome {
    /** The user declined, or the install moved to another screen; nothing went wrong. */
    data object Ended : UpdateInstallOutcome

    /** The install could not be started by any route. */
    data class Failed(val message: String?) : UpdateInstallOutcome
}

internal object UpdateInstallEvents {
    val outcomes = MutableSharedFlow<UpdateInstallOutcome>(extraBufferCapacity = 4)
}

/**
 * Receives the outcome of an update install session. A confirmation request is shown only for installs the user
 * started; a background install that still needs confirmation is dropped and the downloaded APK waits for the banner.
 * A failed session is retried without dex metadata, then falls back to the system installer screen. A session the
 * user declined, or that was abandoned on purpose, is left alone so the user is not asked again.
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        val withDexMetadata = intent.getBooleanExtra(EXTRA_WITH_DEX_METADATA, false)
        val apkFile = intent.getStringExtra(EXTRA_APK_PATH)?.let(::File)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Session $sessionId status=$status background=$background dm=$withDexMetadata message=$message")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (background) {
                    abandon(context, sessionId)
                } else {
                    // The system installer's confirmation screen. It has to be started from here: the session stays
                    // pending, and the app is only replaced once the user confirms it.
                    val launched = confirmationIntent(intent)?.let { confirm ->
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Log.w(TAG, "Install confirmation screen unavailable", it) }
                            .isSuccess
                    } ?: false
                    if (!launched) {
                        abandon(context, sessionId)
                        retryAfterFailure(context, apkFile, background = false, withDexMetadata = false, message = message)
                    }
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateRelaunchReceiver.cancelReopen(context)
                // The abort of a session abandoned above is part of a retry, not the user backing out.
                if (!abandonedSessions.remove(sessionId) && !background) {
                    UpdateInstallEvents.outcomes.tryEmit(UpdateInstallOutcome.Ended)
                }
            }
            else -> {
                UpdateRelaunchReceiver.cancelReopen(context)
                retryAfterFailure(context, apkFile, background, withDexMetadata, message)
            }
        }
    }

    private fun abandon(context: Context, sessionId: Int) {
        abandonedSessions.add(sessionId)
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            .onFailure { abandonedSessions.remove(sessionId) }
    }

    /** Installs again without dex metadata, then through the system installer screen; reports it when nothing is left. */
    private fun retryAfterFailure(
        context: Context,
        apkFile: File?,
        background: Boolean,
        withDexMetadata: Boolean,
        message: String?
    ) {
        if (background) {
            // Nothing is waiting on a background install, which retries on the next idle run.
            if (apkFile != null && apkFile.exists() && withDexMetadata) {
                runCatching { ApkInstaller.install(context, apkFile, background = true, withDexMetadata = false) }
                    .onFailure { Log.w(TAG, "Update install retry failed", it) }
            }
            return
        }
        // Copying the APK into a new session takes longer than a receiver may run, so it moves off the main thread.
        val pending = goAsync()
        thread(name = "update-install-retry") {
            try {
                val outcome = runCatching {
                    check(apkFile != null && apkFile.exists()) { "Downloaded file is missing" }
                    if (withDexMetadata) {
                        ApkInstaller.install(context, apkFile, background = false, withDexMetadata = false)
                        null // another session is waiting for the user
                    } else {
                        ApkInstaller.launchSystemInstaller(context, apkFile)
                        UpdateInstallOutcome.Ended // the system installer screen takes over from here
                    }
                }.getOrElse {
                    Log.w(TAG, "Update install retry failed", it)
                    UpdateInstallOutcome.Failed(message)
                }
                outcome?.let(UpdateInstallEvents.outcomes::tryEmit)
            } finally {
                pending.finish()
            }
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val EXTRA_APK_PATH = "com.nuvio.tv.updater.APK_PATH"
        private const val EXTRA_BACKGROUND = "com.nuvio.tv.updater.BACKGROUND"
        private const val EXTRA_WITH_DEX_METADATA = "com.nuvio.tv.updater.WITH_DEX_METADATA"

        // Sessions this receiver abandoned itself, whose abort broadcast must not read as the user cancelling.
        private val abandonedSessions: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

        fun statusReceiver(
            context: Context,
            sessionId: Int,
            apkFile: File,
            background: Boolean,
            withDexMetadata: Boolean
        ): PendingIntent {
            val intent = Intent(context, UpdateInstallResultReceiver::class.java)
                .putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                .putExtra(EXTRA_BACKGROUND, background)
                .putExtra(EXTRA_WITH_DEX_METADATA, withDexMetadata)
            // The installer adds the status extras, so the intent has to stay mutable.
            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
            )
        }
    }
}
