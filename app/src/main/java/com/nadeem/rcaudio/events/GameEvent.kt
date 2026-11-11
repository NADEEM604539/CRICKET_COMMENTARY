package com.nadeem.rcaudio.events

enum class GameEventType(val isGameEvent: Boolean = true) {
    DOT,
    SINGLE,
    DOUBLE,
    TRIPLE,
    FOUR,
    SIX,
    WICKET,
    WIDE,
    NO_BALL,
    BYE,
    LEG_BYE,
    RUN_OUT,
    OVERTHROW,
    /** Boundary explanation: plays by itself after a 4 or 6 clip finishes. Never sent by the game. */
    EXP_BOUND(false),
    CENTURY,
    OVER_COMPLETED,
    INNINGS_COMPLETED,
    TOSS_BAT(false),
    TOSS_BOWL(false),
    REFRESHMENT(false),
    BOWLED_OUT(false),
    CATCH_OUT(false),
    LBW_OUT(false),
    PITCHED_UP_DOT(false),
    BOUNCER_DOT(false),
}

enum class GameEventSource {
    UNVERIFIED,
    REAL_CRICKET_PACKAGE_BROADCAST,
}

data class GameEvent(
    val eventId: String,
    val type: GameEventType,
    val timestampMs: Long,
    val matchId: String?,
    val innings: Int?,
    val over: Int?,
    val ball: Int?,
    val runsScored: Int?,
    val batRuns: Int?,
    val extras: Int?,
    val extraType: String?,
    val wicket: Boolean?,
    val wicketType: String?,
    val striker: String?,
    val nonStriker: String?,
    val bowler: String?,
    val isReplay: Boolean?,
    val source: GameEventSource,
)

sealed interface EventParseResult {
    data class Accepted(val event: GameEvent) : EventParseResult
    data class Rejected(val reason: String) : EventParseResult
}

object GameEventParser {
    private val eventIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")
    private val identifierPattern = Regex("[A-Za-z0-9._:-]{1,128}")

    fun parse(
        values: Map<String, Any?>,
        source: GameEventSource = GameEventSource.UNVERIFIED,
    ): EventParseResult {
        val eventId = values["event_id"] as? String
            ?: return EventParseResult.Rejected("Missing or invalid event_id")
        if (!eventIdPattern.matches(eventId)) {
            return EventParseResult.Rejected("Invalid event_id")
        }

        val typeName = values["event_type"] as? String
            ?: return EventParseResult.Rejected("Missing or invalid event_type")
        val type = GameEventType.entries.firstOrNull { it.name == typeName && it.isGameEvent }
            ?: return EventParseResult.Rejected("Unsupported event_type")

        val timestampMs = values["timestamp_ms"].asLongOrNull()
            ?: return EventParseResult.Rejected("Missing or invalid timestamp_ms")
        if (timestampMs < 0) return EventParseResult.Rejected("Negative timestamp_ms")

        if (!values.hasValidOptionalInt("innings", 1..20)) {
            return EventParseResult.Rejected("Invalid innings")
        }
        if (!values.hasValidOptionalInt("over", 0..1000)) {
            return EventParseResult.Rejected("Invalid over")
        }
        if (!values.hasValidOptionalInt("ball", 1..12)) {
            return EventParseResult.Rejected("Invalid ball")
        }
        if (!values.hasValidOptionalInt("runs_scored", 0..50)) {
            return EventParseResult.Rejected("Invalid runs_scored")
        }
        if (!values.hasValidOptionalInt("bat_runs", 0..50)) {
            return EventParseResult.Rejected("Invalid bat_runs")
        }
        if (!values.hasValidOptionalInt("extras", 0..50)) {
            return EventParseResult.Rejected("Invalid extras")
        }
        val innings = values["innings"] as? Int
        val over = values["over"] as? Int
        val ball = values["ball"] as? Int
        val runsScored = values["runs_scored"] as? Int
        val batRuns = values["bat_runs"] as? Int
        val extras = values["extras"] as? Int

        val optionalIdentifiers = listOf("match_id", "extra_type", "wicket_type")
        if (optionalIdentifiers.any { !values.hasValidOptionalIdentifier(it) }) {
            return EventParseResult.Rejected("Invalid optional identifier")
        }
        val optionalNames = listOf("striker", "non_striker", "bowler")
        if (optionalNames.any { !values.hasValidOptionalDisplayName(it) }) {
            return EventParseResult.Rejected("Invalid optional player name")
        }
        val matchId = values["match_id"] as? String
        val extraType = values["extra_type"] as? String
        val wicketType = values["wicket_type"] as? String
        val striker = values["striker"] as? String
        val nonStriker = values["non_striker"] as? String
        val bowler = values["bowler"] as? String

        val wicket = values["wicket"]
        if (wicket != null && wicket !is Boolean) {
            return EventParseResult.Rejected("Invalid wicket")
        }
        if (type == GameEventType.WICKET && wicket == false) {
            return EventParseResult.Rejected("WICKET event cannot report wicket=false")
        }
        if (type == GameEventType.RUN_OUT && wicket == false) {
            return EventParseResult.Rejected("RUN_OUT event cannot report wicket=false")
        }
        if (wicketType != null && wicket == false) {
            return EventParseResult.Rejected("wicket_type requires wicket=true")
        }

        val isReplay = values["is_replay"]
        if (isReplay != null && isReplay !is Boolean) {
            return EventParseResult.Rejected("Invalid is_replay")
        }

        return EventParseResult.Accepted(
            GameEvent(
                eventId = eventId,
                type = type,
                timestampMs = timestampMs,
                matchId = matchId,
                innings = innings,
                over = over,
                ball = ball,
                runsScored = runsScored,
                batRuns = batRuns,
                extras = extras,
                extraType = extraType,
                wicket = wicket as? Boolean,
                wicketType = wicketType,
                striker = striker,
                nonStriker = nonStriker,
                bowler = bowler,
                isReplay = isReplay as? Boolean,
                source = source,
            ),
        )
    }

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        else -> null
    }

    private fun Map<String, Any?>.hasValidOptionalInt(key: String, range: IntRange): Boolean =
        !containsKey(key) || ((this[key] as? Int)?.let(range::contains) == true)

    private fun Map<String, Any?>.optionalIdentifier(key: String): String? {
        if (!containsKey(key) || this[key] == null) return null
        val value = this[key] as? String ?: return null
        return value.takeIf(identifierPattern::matches)
    }

    private fun Map<String, Any?>.hasValidOptionalIdentifier(key: String): Boolean =
        !containsKey(key) || this[key] == null || optionalIdentifier(key) != null

    private fun Map<String, Any?>.hasValidOptionalDisplayName(key: String): Boolean {
        if (!containsKey(key) || this[key] == null) return true
        val value = this[key] as? String ?: return false
        return value.isNotBlank() && value.length <= 80 && value.none { Character.isISOControl(it) }
    }
}
