package com.nadeem.rcaudio.overlay

import com.nadeem.rcaudio.events.GameEventType

/** A button on the floating bar. [type] is null for menu and stop controls. */
enum class OverlayButton(val label: String, val type: GameEventType?, val isSubmenuChoice: Boolean = false) {
    DOT("0", null),
    SINGLE("1", GameEventType.SINGLE),
    DOUBLE("2", GameEventType.DOUBLE),
    FOUR("4", GameEventType.FOUR),
    SIX("6", GameEventType.SIX),
    WICKET("W", null),
    EXTRAS("E", null),
    TOSS("T", null),
    REFRESHMENT("R", GameEventType.REFRESHMENT),
    STOP("P", null),
    WIDE("WD", GameEventType.WIDE, isSubmenuChoice = true),
    NO_BALL("NB", GameEventType.NO_BALL, isSubmenuChoice = true),
    BYE("BYE", GameEventType.BYE, isSubmenuChoice = true),
    TOSS_BAT("BAT", GameEventType.TOSS_BAT, isSubmenuChoice = true),
    TOSS_BOWL("BOWL", GameEventType.TOSS_BOWL, isSubmenuChoice = true),
    RUN_OUT("RUN", GameEventType.RUN_OUT, isSubmenuChoice = true),
    BOWLED_OUT("BOWL", GameEventType.BOWLED_OUT, isSubmenuChoice = true),
    CATCH_OUT("CATCH", GameEventType.CATCH_OUT, isSubmenuChoice = true),
    LBW_OUT("LBW", GameEventType.LBW_OUT, isSubmenuChoice = true),
    PITCHED_UP_DOT("PITCH UP", GameEventType.PITCHED_UP_DOT, isSubmenuChoice = true),
    BOUNCER_DOT("BOUNCER", GameEventType.BOUNCER_DOT, isSubmenuChoice = true),
    ;

    /** 4, 6, W and the wicket choices live in their own always-available window, even when the main bar is folded. */
    val isQuick: Boolean
        get() = this in quickRow || this in wicketRow

    companion object {
        /** Main bar (top of the screen, folds away). */
        val mainRow: List<OverlayButton> = listOf(DOT, SINGLE, DOUBLE, EXTRAS)

        /** Important buttons: a separate thumb-reach column at the screen edge. */
        val quickRow: List<OverlayButton> = listOf(FOUR, SIX, WICKET)
        val extrasRow: List<OverlayButton> = listOf(WIDE, NO_BALL, BYE)
        val tossRow: List<OverlayButton> = listOf(TOSS_BAT, TOSS_BOWL)
        val wicketRow: List<OverlayButton> = listOf(RUN_OUT, BOWLED_OUT, CATCH_OUT, LBW_OUT)
        val dotRow: List<OverlayButton> = listOf(PITCHED_UP_DOT, BOUNCER_DOT)
    }
}

enum class OverlaySubmenu {
    EXTRAS,
    TOSS,
    WICKET,
    DOT,
}

/**
 * Behaviour of the bar, kept free of Android classes so it can be unit tested.
 *
 * - Tapping a playback button closes any open submenu and returns its category.
 * - Tapping E or T toggles the matching submenu without playing anything.
 * - Submenu choices are ignored unless their matching submenu is open.
 * - A tap on the same button within [lockoutMs] of its previous accepted tap is ignored.
 */
class OverlayMenuState(
    private val lockoutMs: Long = 800L,
    private val extrasTimeoutMs: Long = DEFAULT_EXTRAS_TIMEOUT_MS,
    initiallyExpanded: Boolean = false,
) {
    var expanded: Boolean = initiallyExpanded
        private set

    var activeSubmenu: OverlaySubmenu? = null
        private set

    val extrasOpen: Boolean
        get() = activeSubmenu == OverlaySubmenu.EXTRAS

    val tossOpen: Boolean
        get() = activeSubmenu == OverlaySubmenu.TOSS

    val wicketOpen: Boolean
        get() = activeSubmenu == OverlaySubmenu.WICKET

    val dotOpen: Boolean
        get() = activeSubmenu == OverlaySubmenu.DOT

    private val lastTapAt = HashMap<OverlayButton, Long>()
    private var submenuOpenedAtMs: Long? = null

    fun setExpanded(value: Boolean) {
        expanded = value
        if (!value) closeExtras()
    }

    /** Returns the event to play for this tap, or null when the tap plays nothing. */
    fun onTap(button: OverlayButton, nowMs: Long): GameEventType? {
        if (!expanded && !button.isQuick) return null
        val requiredSubmenu = button.requiredSubmenu
        if (requiredSubmenu != null && activeSubmenu != requiredSubmenu) return null

        if (!acceptTap(button, nowMs)) return null

        val openedSubmenu = button.openedSubmenu
        if (openedSubmenu != null) {
            if (activeSubmenu == openedSubmenu) {
                closeExtras()
            } else {
                activeSubmenu = openedSubmenu
                submenuOpenedAtMs = nowMs
            }
            return null
        }
        closeExtras()
        return button.type
    }

    fun acceptStopTap(nowMs: Long): Boolean = acceptTap(OverlayButton.STOP, nowMs)

    /** Closes the active submenu once its three-second selection window has elapsed. */
    fun closeExtrasIfTimedOut(nowMs: Long): Boolean {
        val openedAt = submenuOpenedAtMs ?: return false
        if (nowMs - openedAt < extrasTimeoutMs) return false
        closeExtras()
        return true
    }

    fun closeExtras() {
        activeSubmenu = null
        submenuOpenedAtMs = null
    }

    private fun acceptTap(button: OverlayButton, nowMs: Long): Boolean {
        val last = lastTapAt[button]
        if (last != null && nowMs - last < lockoutMs) return false
        lastTapAt[button] = nowMs
        return true
    }

    companion object {
        const val DEFAULT_EXTRAS_TIMEOUT_MS = 3_000L
    }
}

private val OverlayButton.openedSubmenu: OverlaySubmenu?
    get() = when (this) {
        OverlayButton.EXTRAS -> OverlaySubmenu.EXTRAS
        OverlayButton.TOSS -> OverlaySubmenu.TOSS
        OverlayButton.WICKET -> OverlaySubmenu.WICKET
        OverlayButton.DOT -> OverlaySubmenu.DOT
        else -> null
    }

private val OverlayButton.requiredSubmenu: OverlaySubmenu?
    get() = when (this) {
        OverlayButton.WIDE, OverlayButton.NO_BALL, OverlayButton.BYE -> OverlaySubmenu.EXTRAS
        OverlayButton.TOSS_BAT, OverlayButton.TOSS_BOWL -> OverlaySubmenu.TOSS
        OverlayButton.RUN_OUT, OverlayButton.BOWLED_OUT, OverlayButton.CATCH_OUT, OverlayButton.LBW_OUT ->
            OverlaySubmenu.WICKET
        OverlayButton.PITCHED_UP_DOT, OverlayButton.BOUNCER_DOT -> OverlaySubmenu.DOT
        else -> null
    }
