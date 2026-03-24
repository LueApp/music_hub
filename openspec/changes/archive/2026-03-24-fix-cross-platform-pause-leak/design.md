## Context

When Music Hub detects song end for a NetEase song and launches a QQ Music deep link, the following sequence occurs:

1. `pauseAllMedia()` sends `pause()` + `stop()` to NetEase's MediaController
2. NetEase initially pauses (state=2)
3. `scheduleRepeatedPause()` schedules re-pauses at 500ms intervals up to 4000ms
4. ~2 seconds later, NetEase auto-advances to its next song in queue, cycling through states: PAUSED(2) → STOPPED(6) → PLAYING(3)
5. The scheduled re-pauses at 500-2500ms don't catch this because `pauseSpecificPackage` only fires when state is `STATE_PLAYING`, and NetEase isn't in PLAYING state yet during those checks
6. The 3000ms re-pause finally catches NetEase in PLAYING state, but by then the user has heard ~120ms of the wrong song

Additionally, `handlePlaybackStateChange()` has an early return at line 261-263 that ignores **all** state changes from the old platform. This means we can't reactively detect when NetEase starts playing its next song.

### Affected Service

**MediaMonitorService** (`android-app/app/src/main/java/com/musichub/service/MediaMonitorService.kt`) — specifically:
- `handlePlaybackStateChange()` (line 254) — early return ignores old platform events
- `pauseSpecificPackage()` (line 574) — only checks for STATE_PLAYING
- `scheduleRepeatedPause()` (line 559) — timer-only approach

This is standalone/player mode only (not controller mode — controller doesn't run MediaMonitorService locally).

## Goals / Non-Goals

**Goals:**
- Eliminate audible bleed of the old platform's next song during cross-platform transitions
- Reactively pause the old platform the instant it starts playing again, rather than waiting for a scheduled timer tick

**Non-Goals:**
- Changing song-end detection logic
- Modifying deep link launch behavior
- Handling the same issue for Bilibili or QQ Music (not observed)
- Preventing the old app from auto-advancing internally (we can only react to it)

## Decisions

### Decision 1: Add reactive re-pause in `handlePlaybackStateChange`

**Approach:** Before the early return for non-current platforms at line 261, add a check: if we're in a cross-platform switch window (`isCrossPlatformSwitch && switchingFromPackage == fromPackage`) and the old platform enters PLAYING state, immediately pause it.

**Why this over alternatives:**
- **Alternative: Faster polling** — Reducing the scheduled re-pause interval (e.g., every 100ms) would be wasteful CPU-wise and still has a 100ms reaction gap. The callback-based approach reacts instantly (within the same event loop iteration).
- **Alternative: Keep timer-only but add more states** — Could check for BUFFERING/STOPPED in `pauseSpecificPackage`, but that would send unnecessary pause commands to already-paused controllers. The reactive approach is more targeted.

The scheduled re-pauses are kept as a safety net in case the callback somehow doesn't fire.

### Decision 2: Remove `stop()` from `pauseSpecificPackage`

Looking at the logs, after the re-pause at 15:28:54.326, QQ Music's state dropped from 3 to 0 at 54.338. The `stop()` command in `pauseSpecificPackage` is likely being dispatched to all controllers (or the transport system is broadcasting it). Only `pause()` should be used in re-pauses — the initial `pauseAllMedia` can keep `stop()` since it's called before the new platform starts.

### Decision 3: Also re-pause on BUFFERING state, not just PLAYING

NetEase transitions through PAUSED→STOPPED→PLAYING. We should catch it at PLAYING (the earliest audible state). But we should also catch BUFFERING (state=6 in some APIs) since some apps buffer before playing. Checking for `STATE_PLAYING || STATE_BUFFERING` in the reactive handler covers both cases.

## Risks / Trade-offs

- **[Risk] Pause command may not take effect immediately** → Mitigation: Keep the scheduled re-pauses as a backup. The reactive pause fires first; scheduled pauses catch any edge case where the callback was delayed.
- **[Risk] Removing `stop()` from re-pause may make it less effective** → Mitigation: `pause()` alone has been sufficient in the initial `pauseAllMedia` call (NetEase does pause when it receives it). The `stop()` is what may be causing collateral damage to QQ Music.
- **[Risk] Race between reactive re-pause and the 3-second deactivation in `onNewSongStarted`** → Mitigation: The reactive re-pause only fires while `isCrossPlatformSwitch` is true. The 3-second deactivation clears this flag, naturally stopping re-pauses.
