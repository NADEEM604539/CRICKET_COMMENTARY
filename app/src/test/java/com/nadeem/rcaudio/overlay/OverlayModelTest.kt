package com.nadeem.rcaudio.overlay

import com.nadeem.rcaudio.events.GameEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayModelTest {
    @Test
    fun buttonsAppearInThePlannedOrder() {
        assertEquals(
            listOf("0", "1", "2", "E"),
            OverlayButton.mainRow.map { it.label },
        )
        assertEquals(
            listOf("4", "6", "W"),
            OverlayButton.quickRow.map { it.label },
        )
        assertEquals(
            listOf("WD", "NB", "BYE"),
            OverlayButton.extrasRow.map { it.label },
        )
        assertEquals(listOf("BAT", "BOWL"), OverlayButton.tossRow.map { it.label })
        assertEquals(listOf("RUN", "BOWL", "CATCH", "LBW"), OverlayButton.wicketRow.map { it.label })
        assertEquals(listOf("PITCH UP", "BOUNCER"), OverlayButton.dotRow.map { it.label })
        assertEquals(listOf("T", "R"), listOf(OverlayButton.TOSS.label, OverlayButton.REFRESHMENT.label))
        assertEquals("P", OverlayButton.STOP.label)
    }

    @Test
    fun mainButtonsMapToExpectedEvents() {
        val state = OverlayMenuState(initiallyExpanded = true)
        var now = 0L
        val expected = mapOf(
            OverlayButton.SINGLE to GameEventType.SINGLE,
            OverlayButton.DOUBLE to GameEventType.DOUBLE,
            OverlayButton.FOUR to GameEventType.FOUR,
            OverlayButton.SIX to GameEventType.SIX,
        )
        expected.forEach { (button, type) ->
            now += 5_000
            assertEquals(type, state.onTap(button, now))
        }
    }

    @Test
    fun zeroOpensPitchedUpAndBouncerDotPools() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertNull(state.onTap(OverlayButton.DOT, 0))
        assertTrue(state.dotOpen)

        listOf(
            OverlayButton.PITCHED_UP_DOT to GameEventType.PITCHED_UP_DOT,
            OverlayButton.BOUNCER_DOT to GameEventType.BOUNCER_DOT,
        ).forEachIndexed { index, (button, type) ->
            val menu = OverlayMenuState(initiallyExpanded = true)
            menu.onTap(OverlayButton.DOT, 0)
            assertEquals(type, menu.onTap(button, 1_000L + index))
            assertFalse(menu.dotOpen)
        }
    }

    @Test
    fun zeroMenuSwitchesToOtherMenusAndIgnoresChoicesWhenClosed() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.DOT, 0)
        state.onTap(OverlayButton.WICKET, 1_000)
        assertFalse(state.dotOpen)
        assertTrue(state.wicketOpen)
        assertNull(state.onTap(OverlayButton.PITCHED_UP_DOT, 2_000))
    }

    @Test
    fun tappingEOpensExtrasWithoutPlaying() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertNull(state.onTap(OverlayButton.EXTRAS, 0))
        assertTrue(state.extrasOpen)
    }

    @Test
    fun pickingAnExtraPlaysItAndClosesTheRow() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.EXTRAS, 0)
        assertEquals(GameEventType.WIDE, state.onTap(OverlayButton.WIDE, 1_000))
        assertFalse(state.extrasOpen)
    }

    @Test
    fun eachExtraMapsCorrectly() {
        listOf(
            OverlayButton.WIDE to GameEventType.WIDE,
            OverlayButton.NO_BALL to GameEventType.NO_BALL,
            OverlayButton.BYE to GameEventType.BYE,
        ).forEachIndexed { index, (button, type) ->
            val state = OverlayMenuState(initiallyExpanded = true)
            state.onTap(OverlayButton.EXTRAS, 0)
            assertEquals(type, state.onTap(button, 1_000L + index))
        }
    }

    @Test
    fun extrasAreIgnoredWhileRowIsClosed() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertNull(state.onTap(OverlayButton.NO_BALL, 0))
    }

    @Test
    fun tappingEAgainClosesTheRow() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.EXTRAS, 0)
        assertNull(state.onTap(OverlayButton.EXTRAS, 2_000))
        assertFalse(state.extrasOpen)
    }

    @Test
    fun tappingTOpensTossChoicesAndTogglingItClosesThem() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertNull(state.onTap(OverlayButton.TOSS, 0))
        assertTrue(state.tossOpen)
        assertNull(state.onTap(OverlayButton.TOSS, 1_000))
        assertFalse(state.tossOpen)
    }

    @Test
    fun tossChoicesMapToDistinctPlaybackPoolsAndCloseTheMenu() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.TOSS, 0)
        assertEquals(GameEventType.TOSS_BAT, state.onTap(OverlayButton.TOSS_BAT, 1_000))
        assertFalse(state.tossOpen)

        state.onTap(OverlayButton.TOSS, 2_000)
        assertEquals(GameEventType.TOSS_BOWL, state.onTap(OverlayButton.TOSS_BOWL, 3_000))
        assertFalse(state.tossOpen)
    }

    @Test
    fun tossAndExtrasMenusReplaceEachOther() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.TOSS, 0)
        state.onTap(OverlayButton.EXTRAS, 1_000)
        assertTrue(state.extrasOpen)
        assertFalse(state.tossOpen)
        assertNull(state.onTap(OverlayButton.TOSS_BOWL, 2_000))
    }

    @Test
    fun wicketButtonOpensRunOutBowledAndCatchOutChoices() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertNull(state.onTap(OverlayButton.WICKET, 0))
        assertTrue(state.wicketOpen)
        listOf(
            OverlayButton.RUN_OUT to GameEventType.RUN_OUT,
            OverlayButton.BOWLED_OUT to GameEventType.BOWLED_OUT,
            OverlayButton.CATCH_OUT to GameEventType.CATCH_OUT,
            OverlayButton.LBW_OUT to GameEventType.LBW_OUT,
        ).forEachIndexed { index, (button, type) ->
            val menu = OverlayMenuState(initiallyExpanded = true)
            menu.onTap(OverlayButton.WICKET, 0)
            assertEquals(type, menu.onTap(button, 1_000L + index))
            assertFalse(menu.wicketOpen)
        }
    }

    @Test
    fun wicketMenuTogglesClosesAndOtherMenusReplaceIt() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.WICKET, 0)
        state.onTap(OverlayButton.WICKET, 1_000)
        assertFalse(state.wicketOpen)

        state.onTap(OverlayButton.WICKET, 2_000)
        state.onTap(OverlayButton.TOSS, 3_000)
        assertFalse(state.wicketOpen)
        assertTrue(state.tossOpen)
        assertNull(state.onTap(OverlayButton.RUN_OUT, 4_000))
    }

    @Test
    fun refreshmentButtonMapsToItsOwnPool() {
        val state = OverlayMenuState(initiallyExpanded = true)
        assertEquals(GameEventType.REFRESHMENT, state.onTap(OverlayButton.REFRESHMENT, 0))
    }

    @Test
    fun anySelectionClosesTheOpenSubmenu() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.TOSS, 0)
        assertEquals(GameEventType.SIX, state.onTap(OverlayButton.SIX, 1_000))
        assertFalse(state.tossOpen)
    }

    @Test
    fun tappingAMainButtonClosesAnOpenExtrasRowAndStillPlays() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.EXTRAS, 0)
        assertEquals(GameEventType.FOUR, state.onTap(OverlayButton.FOUR, 1_000))
        assertFalse(state.extrasOpen)
    }

    @Test
    fun doubleTapWithinLockoutPlaysOnce() {
        val state = OverlayMenuState(lockoutMs = 800, initiallyExpanded = true)
        assertEquals(GameEventType.SIX, state.onTap(OverlayButton.SIX, 0))
        assertNull(state.onTap(OverlayButton.SIX, 300))
        assertEquals(GameEventType.SIX, state.onTap(OverlayButton.SIX, 900))
    }

    @Test
    fun extrasTimeoutClosesAtThreeSecondsButNotBefore() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.EXTRAS, 0)
        assertFalse(state.closeExtrasIfTimedOut(2_999))
        assertTrue(state.extrasOpen)
        assertTrue(state.closeExtrasIfTimedOut(3_000))
        assertFalse(state.extrasOpen)
    }

    @Test
    fun dotBallMenuUsesTheSameTimeoutAsOtherSubmenus() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.DOT, 0)
        assertTrue(state.dotOpen)
        assertTrue(state.closeExtrasIfTimedOut(OverlayMenuState.DEFAULT_EXTRAS_TIMEOUT_MS))
        assertFalse(state.dotOpen)
    }

    @Test
    fun collapsedModeIgnoresMainBarButtonsUntilExpanded() {
        val state = OverlayMenuState()
        assertFalse(state.expanded)
        assertNull(state.onTap(OverlayButton.SINGLE, 1_000))
        state.setExpanded(true)
        assertEquals(GameEventType.SINGLE, state.onTap(OverlayButton.SINGLE, 2_000))
    }

    @Test
    fun quickButtonsFourSixAndWicketWorkEvenWhenMainBarIsFolded() {
        val state = OverlayMenuState()
        assertFalse(state.expanded)
        assertEquals(GameEventType.FOUR, state.onTap(OverlayButton.FOUR, 1_000))
        assertEquals(GameEventType.SIX, state.onTap(OverlayButton.SIX, 2_000))
        assertNull(state.onTap(OverlayButton.WICKET, 3_000))
        assertTrue(state.wicketOpen)
        assertEquals(GameEventType.CATCH_OUT, state.onTap(OverlayButton.CATCH_OUT, 4_000))
        assertFalse(state.wicketOpen)
    }

    @Test
    fun collapsingClosesTheExtrasRow() {
        val state = OverlayMenuState(initiallyExpanded = true)
        state.onTap(OverlayButton.EXTRAS, 1_000)
        assertTrue(state.extrasOpen)

        state.setExpanded(false)

        assertFalse(state.expanded)
        assertFalse(state.extrasOpen)
        assertNull(state.onTap(OverlayButton.WIDE, 2_000))
    }

    @Test
    fun stopControlWorksInCollapsedAndExpandedModesWithItsOwnLockout() {
        val state = OverlayMenuState(lockoutMs = 800)
        assertTrue(state.acceptStopTap(0))
        assertFalse(state.acceptStopTap(300))
        assertTrue(state.acceptStopTap(800))

        state.setExpanded(true)
        assertTrue(state.acceptStopTap(1_600))
    }
}
