package com.nadeem.rcaudio.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEventParserTest {
    @Test
    fun acceptsValidSixWithContext() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "match-1:innings-1:over-12:ball-4",
                "event_type" to "SIX",
                "timestamp_ms" to 1234L,
                "innings" to 1,
                "over" to 12,
                "ball" to 4,
                "runs_scored" to 6,
                "is_replay" to false,
            ),
        )

        assertTrue(result is EventParseResult.Accepted)
        assertEquals(GameEventType.SIX, (result as EventParseResult.Accepted).event.type)
    }

    @Test
    fun preservesByeInsteadOfInferringSingleFromOneRun() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "event-bye-1",
                "event_type" to "BYE",
                "timestamp_ms" to 10L,
                "runs_scored" to 1,
                "bat_runs" to 0,
                "extras" to 1,
                "extra_type" to "BYE",
            ),
        )

        assertTrue(result is EventParseResult.Accepted)
        val event = (result as EventParseResult.Accepted).event
        assertEquals(GameEventType.BYE, event.type)
        assertEquals(0, event.batRuns)
        assertEquals(1, event.extras)
    }

    @Test
    fun acceptsRunOutWithRunsOnSameDelivery() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "event-run-out-1",
                "event_type" to "RUN_OUT",
                "timestamp_ms" to 11L,
                "runs_scored" to 2,
                "wicket" to true,
                "wicket_type" to "RUN_OUT",
            ),
        )

        assertTrue(result is EventParseResult.Accepted)
        val event = (result as EventParseResult.Accepted).event
        assertEquals(GameEventType.RUN_OUT, event.type)
        assertEquals(2, event.runsScored)
        assertEquals(true, event.wicket)
    }

    @Test
    fun acceptsEventWhenOptionalContextIsUnavailable() {
        val result = GameEventParser.parse(
            mapOf("event_id" to "event-1", "event_type" to "WICKET", "timestamp_ms" to 1L),
        )

        assertTrue(result is EventParseResult.Accepted)
        assertEquals(null, (result as EventParseResult.Accepted).event.innings)
    }

    @Test
    fun rejectsUnknownEventType() {
        val result = GameEventParser.parse(
            mapOf("event_id" to "event-1", "event_type" to "HOME_RUN", "timestamp_ms" to 1L),
        )

        assertTrue(result is EventParseResult.Rejected)
    }

    @Test
    fun rejectsManualOnlyAudioCategoriesAsGameEvents() {
        listOf(
            "TOSS_BAT",
            "TOSS_BOWL",
            "REFRESHMENT",
            "BOWLED_OUT",
            "CATCH_OUT",
            "LBW_OUT",
            "PITCHED_UP_DOT",
            "BOUNCER_DOT",
            "EXP_BOUND",
        ).forEach { type ->
            val result = GameEventParser.parse(
                mapOf("event_id" to "event-1", "event_type" to type, "timestamp_ms" to 1L),
            )
            assertTrue("$type must not be accepted as a game event", result is EventParseResult.Rejected)
        }
    }

    @Test
    fun preservesGameEventTypeAndRunFieldsWithoutReclassifyingOutcome() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "event-1",
                "event_type" to "FOUR",
                "timestamp_ms" to 1L,
                "runs_scored" to 6,
                "bat_runs" to 4,
                "extras" to 2,
            ),
        )

        assertTrue(result is EventParseResult.Accepted)
        val event = (result as EventParseResult.Accepted).event
        assertEquals(GameEventType.FOUR, event.type)
        assertEquals(6, event.runsScored)
        assertEquals(4, event.batRuns)
        assertEquals(2, event.extras)
    }

    @Test
    fun rejectsMalformedOptionalContext() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "event-1",
                "event_type" to "WICKET",
                "timestamp_ms" to 1L,
                "innings" to 0,
            ),
        )

        assertTrue(result is EventParseResult.Rejected)
    }

    @Test
    fun rejectsMalformedOptionalPlayerName() {
        val result = GameEventParser.parse(
            mapOf(
                "event_id" to "event-1",
                "event_type" to "SINGLE",
                "timestamp_ms" to 1L,
                "striker" to 123,
            ),
        )

        assertTrue(result is EventParseResult.Rejected)
    }
}
