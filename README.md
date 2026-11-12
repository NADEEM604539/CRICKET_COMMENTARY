# Real Cricket Audio Companion

An offline Android companion prototype for playing user-selected commentary clips when it receives a verified match event.

## Automatic event detection status

Automatic detection directly from Real Cricket is the goal, but it is **not implemented**: no supported game callback, API, or authorized event sender has been identified. The exported receiver is only an event inbox; it does not discover events by itself.

An in-app `ProcessBuilder`/Logcat reader is not a viable substitute. A regular Android app does not gain access to another app's private logs or Unity runtime events by launching `logcat`, and no scoring-event log output has been verified. A zero-match Logcat search is not proof that the game lacks an internal event pipeline. This project will not claim automatic detection until an authorized source is demonstrated.

The original game remains unmodified. The companion does not use OCR, screen inference, game-audio classification, root access, or code inside the game. Its manifest requests no internet permission.

## Implemented app-side pieces

- Local, per-commentator audio pools mapped to canonical event categories.
- Up to 20 imported clips per event category, with random selection and avoidance of the last three clips when alternatives exist.
- Media3 playback queue, foreground media-playback service, event inbox, and sender UID/package validation for the proposed game broadcast contract.
- Local Compose UI for service control and importing, previewing, and removing audio clips.
- Optional foldable manual overlay: its controls, `0`/`1`/`2`/`E` event buttons, and `4`/`6`/`W` quick buttons are separate, independently movable, text-only frosted-glass groups. Compact mode keeps the control group's draggable handle, expand/collapse toggle, and `P` stop button visible. Expanded mode shows `T`/`R` and the event buttons; `4`/`6`/`W` remain in their own group. `0` opens separate **Pitched Up** and **Bouncer** dot-ball pools; `W` opens `RUN`/`BOWL`/`CATCH`/`LBW` wicket choices. `E` opens `WD`/`NB`/`BYE`; `T` opens Bat/Bowl toss choices. Every category has a separate pool of up to 40 clips. Button taps are not automatic event detection.
- Latest selection wins: a new event, toss/refreshment selection, or clip preview stops the current clip and clears pending playback before it starts. An empty category also stops the prior clip and reports that no audio is mapped. `P` stops current/pending audio without hiding the overlay.
- Boundary explanation: when a 4 or 6 clip plays to its end, one random clip from the **Boundary Explanation** (`EXP_BOUND`, formerly FIFTY) pool plays automatically right after it. Any other tap (or `P`, or tapping 4/6 again) takes over immediately, and then no explanation plays. Nothing plays if the pool is empty. `EXP_BOUND` is manual/automatic-only and rejected by the game-event parser; clips imported under the old FIFTY category are migrated.
- Unit tests for payload validation and clip selection.

The overlay starts collapsed and remembers its expanded/collapsed mode and position. Each category has an independent pool of up to 40 clips with repeat avoidance. Pitched Up Dot, Bouncer Dot, Toss Bat, Toss Bowl, Refreshment, Bowled Out, Catch Out, and LBW are manual-only audio categories and are rejected by the game-event payload parser. The existing DOT category remains available for game-event mappings; Run Out also keeps its own existing category. The overlay sends only an in-app playback request directly to the playback service and bypasses the game receiver. It does not read, hook, patch, or communicate with Real Cricket. A user must grant Android's display-over-other-apps access. Runtime imports are stored as persisted Android document URIs, not bundled audio assets.

## Remaining gate

Obtain a documented publisher integration or developer-authorized callback, then validate actual FOUR, SIX, SINGLE, and DOUBLE events and connect that source to the receiver. Android audio focus can request ducking, but the game/device must honor it; the companion cannot force another app's volume.

See [PLAN_OVERLAY.md](./PLAN_OVERLAY.md).
