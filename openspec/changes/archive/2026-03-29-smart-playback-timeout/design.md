## Context

The playback timeout (currently 5s) detects when a launched song fails to start playing. If `MediaMonitorService.getPlaybackInfo().isPlaying` is false after the timeout, the song is skipped.

The timeout was extended to 20s specifically for "NetEase in landscape" (where `CLEAR_TASK` forces a splash screen). But the same cold-start delay occurs in other scenarios:
- After landscape CLEAR_TASK, subsequent portrait launches also hit the splash screen (no activity stack to reuse)
- Cross-platform switches where the target app isn't running
- Any launch where the target app's MediaController doesn't exist yet

## Goals / Non-Goals

**Goals:**
- Eliminate false-positive skips caused by cold-start splash screens
- Simple heuristic: check if the target app has an active MediaController

**Non-Goals:**
- Predicting exact load times for each app
- Adding retry logic

## Decisions

### 1. Use MediaController presence as the cold/warm indicator

**Decision**: Check if `MediaMonitorService` already has an active `MediaController` for the target package. If yes → warm start (5s). If no → cold start (15s).

**Why**: An active MediaController means the app's MediaSession is registered, which implies the app is running and the player is initialized. No controller means the app needs to start from scratch (splash screen + initialization).

**Rationale**: This replaces both the hardcoded 5s default and the landscape-specific 20s override with a single, platform-agnostic heuristic. The landscape workaround's `CLEAR_TASK` destroys the activity (and often the MediaSession), so after CLEAR_TASK, the controller will be absent → cold start timeout is used automatically.

### 2. Timeout values: 5s warm, 15s cold

**Decision**: Use 5s for warm starts (unchanged), 15s for cold starts.

**Why**: Cold starts need 8-10s for splash screen + player initialization. 15s provides a comfortable buffer without being excessively long for actual failures.

## Risks / Trade-offs

- **[Risk] MediaController persists after CLEAR_TASK**: The old MediaSession might survive briefly after CLEAR_TASK, causing a false warm-start detection.
  → **Mitigation**: The 3s armed delay on the playback callback (from the landscape workaround) already handles this timing issue. Also, if the controller does exist, 5s is usually sufficient since the app is already running.

- **[Trade-off] Longer wait for actual failures**: Cold-start timeout of 15s means genuinely unavailable songs take longer to skip.
  → **Acceptable**: The availability check pre-filters most bad songs. The 15s is only for runtime failures (network issues, etc.) which are rare.
