package com.nadeem.rcaudio.audio

import com.nadeem.rcaudio.events.GameEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundaryExplanationPolicyTest {
    @Test
    fun fourAndSixAreFollowedByABoundaryExplanation() {
        assertEquals(GameEventType.EXP_BOUND, BoundaryExplanationPolicy.followUpFor(GameEventType.FOUR))
        assertEquals(GameEventType.EXP_BOUND, BoundaryExplanationPolicy.followUpFor(GameEventType.SIX))
    }

    @Test
    fun everyOtherEventHasNoFollowUp() {
        GameEventType.entries
            .filterNot { it == GameEventType.FOUR || it == GameEventType.SIX }
            .forEach { type ->
                assertNull("$type must not trigger an explanation", BoundaryExplanationPolicy.followUpFor(type))
            }
    }

    @Test
    fun theExplanationItselfNeverChainsAnotherClip() {
        assertNull(BoundaryExplanationPolicy.followUpFor(GameEventType.EXP_BOUND))
    }
}
