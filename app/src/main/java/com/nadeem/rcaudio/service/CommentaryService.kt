package com.nadeem.rcaudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nadeem.rcaudio.audio.AudioPlaybackEngine
import com.nadeem.rcaudio.events.GameEventType
import com.nadeem.rcaudio.overlay.FloatingOverlayService
import com.nadeem.rcaudio.ui.MainActivity
import org.json.JSONException

class CommentaryService : Service() {
    private lateinit var playbackEngine: AudioPlaybackEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        playbackEngine = AudioPlaybackEngine(this) { status ->
            Log.i(TAG, status)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                // The one explicit "turn everything off" path: pressing Stop in the app or notification.
                setEnabled(false)
                KeepAlive.cancelRevive(this)
                playbackEngine.stop()
                hideOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_PLAYBACK -> {
                playbackEngine.stop()
                return START_STICKY
            }
            ACTION_PLAY_EVENT -> playEvent(intent)
            ACTION_PREVIEW -> {
                val uri = intent.data
                if (uri == null) {
                    Log.e(TAG, "Preview request did not include an audio URI")
                } else {
                    playbackEngine.playPreview(uri)
                }
            }
            ACTION_START -> setEnabled(true)
            null -> Unit
            else -> Log.w(TAG, "Ignoring unknown service action: ${intent.action}")
        }
        return START_STICKY
    }

    /** Swiping the app out of Recents must not stop playback; ask Android to bring us back if it does. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; playback service keeps running")
        if (isEnabled(this)) KeepAlive.scheduleRevive(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::playbackEngine.isInitialized) playbackEngine.release()
        if (isEnabled(this)) KeepAlive.scheduleRevive(this)
        super.onDestroy()
    }

    private fun hideOverlay() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FloatingOverlayService::class.java)
                    .setAction(FloatingOverlayService.ACTION_HIDE),
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not hide the floating buttons", exception)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playEvent(intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val eventTypeName = intent.getStringExtra(EXTRA_EVENT_TYPE)
        if (eventId.isNullOrBlank() || eventTypeName.isNullOrBlank()) {
            Log.e(TAG, "Ignoring event without event ID and type")
            return
        }
        val eventType = GameEventType.entries.firstOrNull { it.name == eventTypeName }
        if (eventType == null) {
            Log.e(TAG, "Ignoring unsupported event type: $eventTypeName")
            return
        }
        val source = intent.getStringExtra(EXTRA_EVENT_SOURCE) ?: "UNVERIFIED"
        Log.i(TAG, "Received ${eventType.name} from $source ($eventId)")
        try {
            playbackEngine.playEventClip(eventId, eventType)
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Invalid clip mapping for ${eventType.name}", exception)
        } catch (exception: JSONException) {
            Log.e(TAG, "Could not read clip history for ${eventType.name}", exception)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Audio permission was revoked for ${eventType.name}", exception)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Commentary playback",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            2,
            Intent(this, CommentaryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Real Cricket Audio Companion")
            .setContentText("Local commentary service active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopService)
            .build()
    }

    companion object {
        const val ACTION_START = "com.nadeem.rcaudio.service.START"
        const val ACTION_STOP = "com.nadeem.rcaudio.service.STOP"
        const val ACTION_STOP_PLAYBACK = "com.nadeem.rcaudio.service.STOP_PLAYBACK"
        const val ACTION_PLAY_EVENT = "com.nadeem.rcaudio.service.PLAY_EVENT"
        const val ACTION_PREVIEW = "com.nadeem.rcaudio.service.PREVIEW"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_EVENT_SOURCE = "event_source"

        private const val PREFERENCES = "commentary_service"
        private const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "rc_commentary_playback"
        private const val NOTIFICATION_ID = 24024
        private const val TAG = "RCCommentaryService"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
    }
}
