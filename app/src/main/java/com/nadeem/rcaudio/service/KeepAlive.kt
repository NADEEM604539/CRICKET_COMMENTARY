package com.nadeem.rcaudio.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nadeem.rcaudio.overlay.FloatingOverlayService

/**
 * Keeps the companion alive the way a VPN app does: it stays up until the user presses Stop
 * inside the app. Closing the app or swiping it out of Recents never stops it.
 */
object KeepAlive {
    private const val TAG = "RCKeepAlive"
    const val ACTION_REVIVE = "com.nadeem.rcaudio.service.REVIVE"
    private const val REVIVE_REQUEST_CODE = 41

    /** True when the user has not pressed Stop/Hide, so something should be running. */
    fun shouldBeRunning(context: Context): Boolean =
        CommentaryService.isEnabled(context) ||
            (Settings.canDrawOverlays(context) && FloatingOverlayService.isEnabled(context))

    /** Starts whichever services the user left switched on. Safe to call repeatedly. */
    fun restore(context: Context) {
        val app = context.applicationContext
        // Overlay first: it is the one the user sees, and it also wakes the playback service.
        // Each start has its own try block because Android 12+ may refuse a foreground-service
        // start from the background (and Android 15 refuses media playback from BOOT_COMPLETED).
        // The playback service is also started on demand by the first button tap.
        try {
            if (Settings.canDrawOverlays(app) && FloatingOverlayService.isEnabled(app)) {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, FloatingOverlayService::class.java).setAction(FloatingOverlayService.ACTION_SHOW),
                )
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not restore the floating buttons right now", exception)
        }
        try {
            if (CommentaryService.isEnabled(app)) {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, CommentaryService::class.java).setAction(CommentaryService.ACTION_START),
                )
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not restore the playback service right now", exception)
        }
    }

    /** Asks Android to run [restore] again shortly, e.g. after the task is swiped away. */
    fun scheduleRevive(context: Context, delayMs: Long = 2_000L) {
        val app = context.applicationContext
        if (!shouldBeRunning(app)) return
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            app,
            REVIVE_REQUEST_CODE,
            Intent(app, RestoreReceiver::class.java).setAction(ACTION_REVIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pending,
        )
    }

    fun cancelRevive(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                app,
                REVIVE_REQUEST_CODE,
                Intent(app, RestoreReceiver::class.java).setAction(ACTION_REVIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/** Restores the floating buttons and playback service after boot, app update, or a revive alarm. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            KeepAlive.ACTION_REVIVE,
            -> KeepAlive.restore(context)
            else -> Unit
        }
    }
}
