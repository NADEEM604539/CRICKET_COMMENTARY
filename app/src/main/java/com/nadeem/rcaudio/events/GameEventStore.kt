package com.nadeem.rcaudio.events

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class EventStoreResult {
    RECORDED,
    DUPLICATE,
    QUEUE_FULL,
}

class GameEventStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(event: GameEvent): EventStoreResult = synchronized(lock) {
        val recentIds = readRecentIds()
        if (event.eventId in recentIds) return@synchronized EventStoreResult.DUPLICATE

        val queue = readQueue()
        if (queue.size >= MAX_QUEUED_EVENTS) return@synchronized EventStoreResult.QUEUE_FULL

        val updatedQueue = queue + event
        val updatedIds = (recentIds + event.eventId).takeLast(MAX_RECENT_IDS)
        val queueJson = JSONArray().apply { updatedQueue.forEach { put(it.toJson()) } }
        val idsJson = JSONArray().apply { updatedIds.forEach { put(it) } }
        preferences.edit()
            .putString(KEY_QUEUE, queueJson.toString())
            .putString(KEY_RECENT_IDS, idsJson.toString())
            .apply()
        EventStoreResult.RECORDED
    }

    fun pendingEvents(): List<GameEvent> = synchronized(lock) { readQueue() }

    fun remove(eventId: String): Boolean = synchronized(lock) {
        val queue = readQueue()
        val updated = queue.filterNot { it.eventId == eventId }
        if (updated.size == queue.size) return@synchronized false

        val json = JSONArray()
        updated.forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY_QUEUE, json.toString()).apply()
        true
    }

    private fun readQueue(): List<GameEvent> {
        val raw = preferences.getString(KEY_QUEUE, null) ?: return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getJSONObject(index).toGameEvent() }
    }

    private fun readRecentIds(): List<String> {
        val raw = preferences.getString(KEY_RECENT_IDS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getString(index) }
    }

    private fun GameEvent.toJson() = JSONObject()
        .put("event_id", eventId)
        .put("event_type", type.name)
        .put("timestamp_ms", timestampMs)
        .put("source", source.name)
        .apply {
            matchId?.let { put("match_id", it) }
            innings?.let { put("innings", it) }
            over?.let { put("over", it) }
            ball?.let { put("ball", it) }
            runsScored?.let { put("runs_scored", it) }
            batRuns?.let { put("bat_runs", it) }
            extras?.let { put("extras", it) }
            extraType?.let { put("extra_type", it) }
            wicket?.let { put("wicket", it) }
            wicketType?.let { put("wicket_type", it) }
            striker?.let { put("striker", it) }
            nonStriker?.let { put("non_striker", it) }
            bowler?.let { put("bowler", it) }
            isReplay?.let { put("is_replay", it) }
        }

    private fun JSONObject.toGameEvent() = GameEvent(
        eventId = getString("event_id"),
        type = GameEventType.valueOf(getString("event_type")),
        timestampMs = getLong("timestamp_ms"),
        matchId = optString("match_id").takeIf(String::isNotEmpty),
        innings = optionalInt("innings"),
        over = optionalInt("over"),
        ball = optionalInt("ball"),
        runsScored = optionalInt("runs_scored"),
        batRuns = optionalInt("bat_runs"),
        extras = optionalInt("extras"),
        extraType = optString("extra_type").takeIf(String::isNotEmpty),
        wicket = if (has("wicket")) getBoolean("wicket") else null,
        wicketType = optString("wicket_type").takeIf(String::isNotEmpty),
        striker = optString("striker").takeIf(String::isNotEmpty),
        nonStriker = optString("non_striker").takeIf(String::isNotEmpty),
        bowler = optString("bowler").takeIf(String::isNotEmpty),
        isReplay = if (has("is_replay")) getBoolean("is_replay") else null,
        source = if (has("source")) {
            GameEventSource.valueOf(getString("source"))
        } else {
            GameEventSource.UNVERIFIED
        },
    )

    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    companion object {
        private val lock = Any()
        private const val PREFERENCES = "game_event_inbox"
        private const val KEY_QUEUE = "pending_events"
        private const val KEY_RECENT_IDS = "recent_event_ids"
        private const val MAX_QUEUED_EVENTS = 128
        private const val MAX_RECENT_IDS = 256
    }
}
