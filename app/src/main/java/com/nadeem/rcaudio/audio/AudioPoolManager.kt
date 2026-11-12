package com.nadeem.rcaudio.audio

import android.content.Context
import android.net.Uri
import com.nadeem.rcaudio.events.GameEventType
import org.json.JSONArray
import java.util.Base64

class AudioPoolManager(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        synchronized(lock) { migrateLegacyFiftyPool() }
    }

    /**
     * The old FIFTY category was renamed EXP_BOUND (boundary explanation). Carry any clips the user already
     * imported under FIFTY (and their repeat history) over so nothing has to be re-imported.
     */
    private fun migrateLegacyFiftyPool() {
        val legacyKeys = preferences.all.keys.filter {
            (it.startsWith("clips_") || it.startsWith("recent_")) && it.endsWith(LEGACY_SUFFIX)
        }
        if (legacyKeys.isEmpty()) return
        val editor = preferences.edit()
        legacyKeys.forEach { key ->
            val newKey = key.removeSuffix(LEGACY_SUFFIX) + "_EXP_BOUND"
            if (key.startsWith("clips_")) {
                val moved = preferences.getStringSet(key, emptySet()).orEmpty()
                val existing = preferences.getStringSet(newKey, emptySet()).orEmpty()
                editor.putStringSet(newKey, (existing + moved).sorted().take(MAX_CLIPS_PER_EVENT).toSet())
            } else if (!preferences.contains(newKey)) {
                editor.putString(newKey, preferences.getString(key, null))
            }
            editor.remove(key)
        }
        editor.apply()
    }

    fun commentators(): List<String> = synchronized(lock) { readCommentators() }

    fun selectedCommentator(): String = synchronized(lock) {
        preferences.getString(KEY_SELECTED_COMMENTATOR, null)
            ?: run {
                createCommentator(DEFAULT_COMMENTATOR)
                DEFAULT_COMMENTATOR
            }
    }

    fun createCommentator(name: String): Boolean = synchronized(lock) {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_COMMENTATOR_NAME) return@synchronized false
        val updated = readCommentators().toMutableSet()
        updated.add(normalized)
        val json = JSONArray().apply { updated.sorted().forEach { put(it) } }
        preferences.edit()
            .putString(KEY_COMMENTATORS, json.toString())
            .putString(KEY_SELECTED_COMMENTATOR, normalized)
            .apply()
        true
    }

    fun selectCommentator(name: String): Boolean = synchronized(lock) {
        if (name !in readCommentators()) return@synchronized false
        preferences.edit().putString(KEY_SELECTED_COMMENTATOR, name).apply()
        true
    }

    fun clipsFor(type: GameEventType): List<Uri> =
        synchronized(lock) {
            preferences.getStringSet(clipsKey(type), emptySet())
                .orEmpty()
                .sorted()
                .map(Uri::parse)
        }

    fun canAddClip(type: GameEventType, uri: Uri): Boolean = synchronized(lock) {
        val existing = preferences.getStringSet(clipsKey(type), emptySet()).orEmpty()
        ClipPoolPolicy.canAdd(existing, uri.toString(), MAX_CLIPS_PER_EVENT)
    }

    fun addClip(type: GameEventType, uri: Uri): Boolean =
        synchronized(lock) {
            val updated = preferences.getStringSet(clipsKey(type), emptySet()).orEmpty().toMutableSet()
            if (uri.toString() in updated) return@synchronized true
            if (updated.size >= MAX_CLIPS_PER_EVENT) return@synchronized false
            updated += uri.toString()
            preferences.edit().putStringSet(clipsKey(type), updated).apply()
            true
        }

    fun removeClip(type: GameEventType, uri: Uri) {
        synchronized(lock) {
            val updated = preferences.getStringSet(clipsKey(type), emptySet()).orEmpty().toMutableSet()
            updated -= uri.toString()
            preferences.edit().putStringSet(clipsKey(type), updated).apply()
        }
    }

    fun chooseClip(type: GameEventType): Uri? = synchronized(lock) {
        val candidates = preferences.getStringSet(clipsKey(type), emptySet())
            .orEmpty()
            .toList()
            .sorted()
        if (candidates.isEmpty()) return@synchronized null

        val recent = readRecent(type)
        val selected = ClipSelectionPolicy.choose(candidates, recent) ?: return@synchronized null
        val updatedRecent = (recent + selected).takeLast(REPEAT_WINDOW)
        val recentJson = JSONArray().apply { updatedRecent.forEach { put(it) } }
        preferences.edit().putString(historyKey(type), recentJson.toString()).apply()
        Uri.parse(selected)
    }

    private fun readRecent(type: GameEventType): List<String> {
        val raw = preferences.getString(historyKey(type), null) ?: return emptyList()
        val json = JSONArray(raw)
        return List(json.length()) { index -> json.getString(index) }
    }

    private fun readCommentators(): List<String> {
        val raw = preferences.getString(KEY_COMMENTATORS, null)
        val existing = if (raw == null) emptyList() else {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getString(index) }
        }
        if (existing.isNotEmpty()) return existing
        val json = JSONArray().put(DEFAULT_COMMENTATOR)
        preferences.edit()
            .putString(KEY_COMMENTATORS, json.toString())
            .putString(KEY_SELECTED_COMMENTATOR, DEFAULT_COMMENTATOR)
            .apply()
        return listOf(DEFAULT_COMMENTATOR)
    }

    private fun clipsKey(type: GameEventType) =
        "clips_${commentatorKey(selectedCommentator())}_${type.name}"

    private fun historyKey(type: GameEventType) =
        "recent_${commentatorKey(selectedCommentator())}_${type.name}"

    private fun commentatorKey(name: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))

    companion object {
        private const val PREFERENCES = "audio_clip_registry"
        private const val REPEAT_WINDOW = 3
        private const val KEY_COMMENTATORS = "commentators"
        private const val KEY_SELECTED_COMMENTATOR = "selected_commentator"
        private const val DEFAULT_COMMENTATOR = "Default"
        private const val LEGACY_SUFFIX = "_FIFTY"
        private const val MAX_COMMENTATOR_NAME = 40
        private val lock = Any()

        const val MAX_CLIPS_PER_EVENT = 40
    }
}

object ClipSelectionPolicy {
    fun choose(candidates: List<String>, recentlyPlayed: List<String>): String? {
        if (candidates.isEmpty()) return null
        val recentSet = recentlyPlayed.toSet()
        val eligible = candidates.filterNot { it in recentSet }.ifEmpty {
            candidates.filterNot { it == recentlyPlayed.lastOrNull() }.ifEmpty { candidates }
        }
        return eligible.random()
    }
}

object ClipPoolPolicy {
    fun canAdd(existing: Set<String>, candidate: String, maximum: Int): Boolean =
        candidate in existing || existing.size < maximum
}
