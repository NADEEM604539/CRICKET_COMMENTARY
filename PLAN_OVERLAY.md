# Plan: Foldable Floating Event Buttons (manual trigger, no game contact)

## Goal
While playing Real Cricket 24, small floating buttons sit over the game. Tapping one plays a random
clip from that category (user's own audio) in the background. Nothing reads, hooks, patches or
modifies the game, so there is no anti-cheat exposure from our side.

## Hard constraints (from the user)
- No Frida, no APK patching, no code inside the game process, no screen/audio capture of the game.
- Account safety first. Test in an offline/practice match before relying on it.

## Button design
The overlay uses separate, transparent, independently movable text-button groups:
- Control row: drag handle, expand/collapse, `P` (always visible); expanded mode adds `T` and `R`.
- Event row: drag handle and `0` `1` `2` `E`.
- Quick column: drag handle and `4` `6` `W`.

The event buttons are foldable:
- It starts collapsed as a compact `≡` drag handle plus `+` expand toggle, so the full chain does not obstruct play.
- Tap `+` to expand the event controls; tap `−` to collapse back to the compact handle.
- Each group's on-screen position and the expanded/collapsed mode are saved locally and restored on the next service start.
- Collapsing closes the extras row. Event buttons are ignored while collapsed.

| Button | Event (GameEventType) |
|--------|-----------------------|
| 0 | opens dot-ball choices (does not play anything) |
| 1 | SINGLE |
| 2 | DOUBLE |
| 4 | FOUR |
| 6 | SIX |
| W | opens wicket choices (does not play anything) |
| E | opens the extras row (does not play anything) |
| T | opens the toss choices (does not play anything) |
| R | REFRESHMENT |
| P | stops current and pending audio; available in compact and expanded modes |

Extras row (appears under the bar after tapping `E`): `WD` -> WIDE, `NB` -> NO_BALL, `BYE` -> BYE.
- Picking one plays its clip and the extras row disappears immediately.
- Tapping `E` again, tapping a main button, or waiting ~3 s with no pick also closes it.
- Per-button lockout of ~0.8 s so an accidental double tap does not play two clips.
- Toss choices: `BAT` -> TOSS_BAT and `BOWL` -> TOSS_BOWL. These are separate manual audio pools.
- Wicket choices: `RUN` -> RUN_OUT, `BOWL` -> BOWLED_OUT, and `CATCH` -> CATCH_OUT. Each selection uses its own audio pool with the standard 40-clip maximum.
- Dot-ball choices: `PITCH UP` -> PITCHED_UP_DOT and `BOUNCER` -> BOUNCER_DOT. Each selection uses a separate 40-clip pool; the existing DOT category remains available for game-event mappings.
- A new event, toss choice, refreshment selection, or preview immediately stops current playback and
  clears pending requests before starting the selected clip. If its pool is empty, the old clip is
  still stopped and the service reports that no audio is mapped.
- `P` stops current and queued playback without hiding the overlay.

## Architecture
```
Overlay button tap
  -> FloatingOverlayService (WindowManager overlay, plain Views)
  -> ACTION_PLAY_EVENT intent to CommentaryService (existing)
  -> AudioPlaybackEngine.playEventClip (latest selection replaces current and pending audio)
  -> AudioPoolManager.chooseClip (existing: up to 40 clips/category, avoids last 3)
  -> Media3 playback with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (existing)
```
Overlay taps go straight to the service. They do NOT go through GameEventReceiver, so the sender
UID check is untouched and no spoofing surface is added.

## Files
New:
- `overlay/OverlayModel.kt`        pure logic: button list + extras-menu state machine (unit tested)
- `overlay/FloatingOverlayService.kt`  foreground service that owns the overlay window
- `ui/OverlayControlCard.kt`       dashboard card: grant permission, show/hide buttons
- `app/src/test/.../OverlayModelTest.kt`  tests for mapping + extras behaviour

Changed:
- `AndroidManifest.xml`   SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE_SPECIAL_USE, register the service
- `ui/MainActivity.kt`    insert the overlay card into the dashboard

## Progress
- [x] Plan, pure button/menu model, overlay service, dashboard card, and manifest setup.
- [x] Unit tests for mapping, ordering, extras behavior, timeout, and debounce.
- [x] Compact/expanded toggle, collapsed-mode event suppression, and collapse-closes-extras behavior.
- [x] Latest-selection-wins playback; `P` stop control; Toss Bat/Bowl submenu and Refreshment pool.
- [x] Wicket submenu with independent Run Out, Bowled Out, and Catch Out audio pools; compact split layout.
- [x] Transparent text-only button groups with independent positions.
- [x] Dot submenu with separate Pitched Up and Bouncer audio pools.
- [x] Mapper UI exposes separate Toss · Bat, Toss · Bowl, Refreshment, Bowled Out, and Catch Out pools; Run Out uses its own existing pool.
- [x] Build and run the test suite.
- [x] Install and launch the companion on the connected phone.
- [x] Grant overlay access and verify the overlay window appears over the game.
- [x] Verify event taps reach CommentaryService; a mapped FOUR clip reached Media3/MP3 decoding.
- [ ] Verify fold/expand, dragging and saved state on-device; the game is currently foreground and was not disturbed for these checks.
- [ ] Verify actual audible playback and focus/ducking behavior.

## Known limits (stated up front)
- Manual: it plays when YOU tap, it does not detect balls by itself.
- Ducking only happens if the game honours Android audio focus. I saw no sign it requests focus,
  so commentary may play over the game audio without lowering it. To be tested.
- Needs the "Display over other apps" permission and the playback notification.
- Starts collapsed by default; expand only when needed. The compact handle remains visible for moving and reopening the controls.
- New selections interrupt current audio; `P` clears audio while leaving the overlay visible.
- Overlay touches could in theory be treated as tapjacking by a game; nothing in the logs suggests
  this one does, but test offline first.

## Out of scope
- This overlay is manual control, not automatic match-event detection.
- Do not add a scoreboard watcher, OCR, screen/audio capture, game hooks, or APK modification under this plan.
- A developer test screen for all event categories is a separate task; button taps can be exercised against configured clip pools.
