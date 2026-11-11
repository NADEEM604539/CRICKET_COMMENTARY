package com.nadeem.rcaudio.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.BadParcelableException
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.nadeem.rcaudio.service.CommentaryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONException

class GameEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GAME_EVENT) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.e(TAG, "Rejecting game event: sender UID verification requires Android 14+")
            return
        }

        val senderUid = sentFromUid
        if (senderUid == Process.INVALID_UID) {
            Log.w(TAG, "Rejecting game event without a verified sender UID")
            return
        }
        val extras = intent.extras ?: Bundle.EMPTY
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val senderPackages = context.packageManager.getPackagesForUid(senderUid)
                if (senderPackages?.contains(GAME_PACKAGE) != true) {
                    Log.w(TAG, "Rejecting game event from an unrecognized package")
                    return@launch
                }

                val payload = extras.keySet().associateWith { key -> extras.get(key) }
                when (
                    val result = GameEventParser.parse(
                        payload,
                        source = GameEventSource.REAL_CRICKET_PACKAGE_BROADCAST,
                    )
                ) {
                    is EventParseResult.Rejected -> {
                        Log.w(TAG, "Rejecting malformed game event: ${result.reason}")
                    }
                    is EventParseResult.Accepted -> {
                        when (GameEventStore(context).record(result.event)) {
                            EventStoreResult.RECORDED -> {
                                Log.i(TAG, "Accepted game event ${result.event.type} (${result.event.eventId})")
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, CommentaryService::class.java)
                                        .setAction(CommentaryService.ACTION_PLAY_EVENT)
                                        .putExtra(CommentaryService.EXTRA_EVENT_ID, result.event.eventId)
                                        .putExtra(CommentaryService.EXTRA_EVENT_TYPE, result.event.type.name)
                                        .putExtra(CommentaryService.EXTRA_EVENT_SOURCE, result.event.source.name),
                                )
                            }
                            EventStoreResult.DUPLICATE ->
                                Log.i(TAG, "Ignored duplicate game event ${result.event.eventId}")
                            EventStoreResult.QUEUE_FULL ->
                                Log.e(TAG, "Event inbox is full; rejected ${result.event.eventId}")
                        }
                    }
                }
            } catch (exception: SecurityException) {
                Log.e(TAG, "Android denied game-event processing or playback startup", exception)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Game-event receiver could not persist or forward the event", exception)
            } catch (exception: JSONException) {
                Log.e(TAG, "Stored game-event data is invalid", exception)
            } catch (exception: BadParcelableException) {
                Log.e(TAG, "Could not read game-event broadcast payload", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_GAME_EVENT = "com.nadeem.rcaudio.GAME_EVENT"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_TIMESTAMP_MS = "timestamp_ms"
        const val EXTRA_MATCH_ID = "match_id"
        const val EXTRA_INNINGS = "innings"
        const val EXTRA_OVER = "over"
        const val EXTRA_BALL = "ball"
        const val EXTRA_RUNS_SCORED = "runs_scored"
        const val EXTRA_BAT_RUNS = "bat_runs"
        const val EXTRA_EXTRAS = "extras"
        const val EXTRA_EXTRA_TYPE = "extra_type"
        const val EXTRA_WICKET = "wicket"
        const val EXTRA_WICKET_TYPE = "wicket_type"
        const val EXTRA_STRIKER = "striker"
        const val EXTRA_NON_STRIKER = "non_striker"
        const val EXTRA_BOWLER = "bowler"
        const val EXTRA_IS_REPLAY = "is_replay"

        private const val GAME_PACKAGE = "com.nautilus.realcricket"
        private const val TAG = "RCGameEvent"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
