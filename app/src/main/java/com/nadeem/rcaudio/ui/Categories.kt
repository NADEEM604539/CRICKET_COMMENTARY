package com.nadeem.rcaudio.ui

import com.nadeem.rcaudio.events.GameEventType

/** A titled group of clip categories shown together in the library. */
internal data class CategoryGroup(val title: String, val types: List<GameEventType>)

/** Categories the floating buttons actually play, grouped the way a scorer thinks about a ball. */
internal val primaryGroups: List<CategoryGroup> = listOf(
    CategoryGroup(
        "Runs",
        listOf(GameEventType.SINGLE, GameEventType.DOUBLE, GameEventType.FOUR, GameEventType.SIX),
    ),
    CategoryGroup(
        "Dot balls",
        listOf(GameEventType.PITCHED_UP_DOT, GameEventType.BOUNCER_DOT),
    ),
    CategoryGroup(
        "Wickets",
        listOf(
            GameEventType.BOWLED_OUT,
            GameEventType.CATCH_OUT,
            GameEventType.LBW_OUT,
            GameEventType.RUN_OUT,
        ),
    ),
    CategoryGroup(
        "Extras",
        listOf(GameEventType.WIDE, GameEventType.NO_BALL, GameEventType.BYE),
    ),
    CategoryGroup(
        "Specials",
        listOf(
            GameEventType.EXP_BOUND,
            GameEventType.TOSS_BAT,
            GameEventType.TOSS_BOWL,
            GameEventType.REFRESHMENT,
        ),
    ),
)

/** Every remaining category, kept reachable so clips imported earlier are never hidden. */
internal val otherTypes: List<GameEventType> =
    GameEventType.entries - primaryGroups.flatMap { it.types }.toSet()

internal fun GameEventType.title(): String = when (this) {
    GameEventType.SINGLE -> "Single"
    GameEventType.DOUBLE -> "Double"
    GameEventType.FOUR -> "Four"
    GameEventType.SIX -> "Six"
    GameEventType.PITCHED_UP_DOT -> "Pitched up"
    GameEventType.BOUNCER_DOT -> "Bouncer"
    GameEventType.BOWLED_OUT -> "Bowled"
    GameEventType.CATCH_OUT -> "Catch out"
    GameEventType.LBW_OUT -> "LBW"
    GameEventType.RUN_OUT -> "Run out"
    GameEventType.WIDE -> "Wide"
    GameEventType.NO_BALL -> "No ball"
    GameEventType.BYE -> "Bye"
    GameEventType.EXP_BOUND -> "Boundary explanation"
    GameEventType.TOSS_BAT -> "Toss: bat"
    GameEventType.TOSS_BOWL -> "Toss: bowl"
    GameEventType.REFRESHMENT -> "Refreshment"
    GameEventType.DOT -> "Dot (plain)"
    GameEventType.TRIPLE -> "Triple"
    GameEventType.WICKET -> "Wicket (generic)"
    GameEventType.LEG_BYE -> "Leg bye"
    GameEventType.OVERTHROW -> "Overthrow"
    GameEventType.CENTURY -> "Century"
    GameEventType.OVER_COMPLETED -> "Over completed"
    GameEventType.INNINGS_COMPLETED -> "Innings completed"
}

/** The short tag shown in the round badge; mirrors the label on the matching floating button. */
internal fun GameEventType.badge(): String = when (this) {
    GameEventType.SINGLE -> "1"
    GameEventType.DOUBLE -> "2"
    GameEventType.TRIPLE -> "3"
    GameEventType.FOUR -> "4"
    GameEventType.SIX -> "6"
    GameEventType.WIDE -> "WD"
    GameEventType.NO_BALL -> "NB"
    GameEventType.BYE -> "BYE"
    GameEventType.LEG_BYE -> "LB"
    GameEventType.RUN_OUT -> "RO"
    GameEventType.BOWLED_OUT -> "B"
    GameEventType.CATCH_OUT -> "C"
    GameEventType.LBW_OUT -> "LBW"
    GameEventType.WICKET -> "W"
    GameEventType.PITCHED_UP_DOT -> "PU"
    GameEventType.BOUNCER_DOT -> "BN"
    GameEventType.DOT -> "0"
    GameEventType.EXP_BOUND -> "EXP"
    GameEventType.TOSS_BAT -> "BAT"
    GameEventType.TOSS_BOWL -> "BWL"
    GameEventType.REFRESHMENT -> "R"
    GameEventType.OVERTHROW -> "OT"
    GameEventType.CENTURY -> "100"
    GameEventType.OVER_COMPLETED -> "OV"
    GameEventType.INNINGS_COMPLETED -> "INN"
}

/** One line explaining when this category plays. */
internal fun GameEventType.hint(): String = when (this) {
    GameEventType.EXP_BOUND -> "Plays by itself, one random clip, right after a 4 or 6 clip finishes."
    GameEventType.TOSS_BAT, GameEventType.TOSS_BOWL, GameEventType.REFRESHMENT ->
        "Manual only: plays when you tap its floating button."
    GameEventType.PITCHED_UP_DOT, GameEventType.BOUNCER_DOT ->
        "Opens from the 0 button on the floating bar."
    GameEventType.BOWLED_OUT, GameEventType.CATCH_OUT, GameEventType.LBW_OUT, GameEventType.RUN_OUT ->
        "Opens from the W button on the floating column."
    GameEventType.WIDE, GameEventType.NO_BALL, GameEventType.BYE ->
        "Opens from the E button on the floating bar."
    else -> "A random clip plays each time; the last three played are skipped when possible."
}
