package com.nadeem.rcaudio.audio

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nadeem.rcaudio.events.GameEventType
import org.json.JSONException
import java.util.ArrayDeque

data class PlaybackRequest(
    val eventId: String,
    val eventType: GameEventType,
    val uri: String,
)

/**
 * Decides which clip category (if any) should follow a clip that has just played to its end.
 * A finished 4 or 6 clip is followed by one random boundary-explanation (EXP_BOUND) clip.
 */
object BoundaryExplanationPolicy {
    fun followUpFor(finished: GameEventType): GameEventType? = when (finished) {
        GameEventType.FOUR, GameEventType.SIX -> GameEventType.EXP_BOUND
        else -> null
    }
}

class PlaybackRequestQueue {
    private val requests = ArrayDeque<PlaybackRequest>()

    fun enqueue(request: PlaybackRequest, replaceQueued: Boolean = false) {
        if (replaceQueued) requests.clear()
        requests.addLast(request)
    }

    fun poll(): PlaybackRequest? = requests.pollFirst()

    fun clear() = requests.clear()

    val isEmpty: Boolean
        get() = requests.isEmpty()
}

class AudioPlaybackEngine(
    context: Context,
    private val registry: AudioPoolManager = AudioPoolManager(context),
    private val focusManager: AudioFocusManager = AudioFocusManager(context),
    private val onStatus: (String) -> Unit = {},
) {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        // Keep the CPU awake while a clip plays so commentary works with the screen off.
        setWakeMode(C.WAKE_MODE_LOCAL)
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishCurrentClip(playedToEnd = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                onStatus("Audio error: ${error.errorCodeName}")
                finishCurrentClip(playedToEnd = false)
            }
        })
    }
    private val queue = PlaybackRequestQueue()
    private var current: PlaybackRequest? = null
    private var wasPlayingBeforeTransientLoss = false

    fun playEventClip(
        eventId: String,
        type: GameEventType,
    ): Boolean {
        queue.clear()
        stopCurrentClip()
        val uri = registry.chooseClip(type) ?: run {
            onStatus("No audio mapped for ${type.name}")
            return false
        }
        queue.enqueue(PlaybackRequest(eventId, type, uri.toString()))
        playNextIfIdle()
        return true
    }

    fun playPreview(uri: Uri) {
        queue.clear()
        stopCurrentClip()
        queue.enqueue(PlaybackRequest("preview-${System.nanoTime()}", GameEventType.DOT, uri.toString()))
        playNextIfIdle()
    }

    fun stop() {
        queue.clear()
        stopCurrentClip()
        onStatus("Playback stopped")
    }

    fun release() {
        queue.clear()
        focusManager.abandon()
        player.release()
    }

    private fun playNextIfIdle() {
        if (current != null || queue.isEmpty) return
        if (!focusManager.request(::onAudioFocusChanged)) {
            // The game may hold focus. Commentary is meant to play over it, so play anyway.
            onStatus("Audio focus not granted; playing anyway")
        }
        val request = queue.poll() ?: return
        current = request
        onStatus("Playing ${request.eventType.name}")
        player.setMediaItem(MediaItem.fromUri(Uri.parse(request.uri)))
        player.prepare()
        player.play()
    }

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeTransientLoss) player.play()
                wasPlayingBeforeTransientLoss = false
                if (current == null) playNextIfIdle()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeTransientLoss = player.isPlaying
                player.pause()
            }
            // The system lowers our volume by itself (willPauseWhenDucked = false); keep playing.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeTransientLoss = false
                player.pause()
                onStatus("Paused after permanent audio focus loss")
            }
        }
    }

    /**
     * Called when the current clip ends by itself (or fails). Stops, replacements and previews never come
     * through here: they clear [current] first, so a boundary explanation can only follow a 4/6 clip that
     * really played to its end without anything else having taken over.
     */
    private fun finishCurrentClip(playedToEnd: Boolean) {
        val finished = current
        current = null
        wasPlayingBeforeTransientLoss = false
        focusManager.abandon()
        if (playedToEnd && finished != null) queueFollowUp(finished)
        playNextIfIdle()
    }

    private fun queueFollowUp(finished: PlaybackRequest) {
        val followUpType = BoundaryExplanationPolicy.followUpFor(finished.eventType) ?: return
        try {
            // No explanation clips imported for this voice pack: simply nothing extra plays.
            val uri = registry.chooseClip(followUpType) ?: return
            queue.enqueue(PlaybackRequest("${finished.eventId}:explain", followUpType, uri.toString()))
        } catch (exception: JSONException) {
            onStatus("Could not pick a ${followUpType.name} clip: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            onStatus("Invalid ${followUpType.name} clip mapping: ${exception.message}")
        }
    }

    private fun stopCurrentClip() {
        current = null
        wasPlayingBeforeTransientLoss = false
        player.stop()
        player.clearMediaItems()
        focusManager.abandon()
    }
}
