## Why

The 5-second playback timeout causes songs to be incorrectly skipped when the target music app needs to go through its launch/splash screen. This happens in two scenarios:

1. **After landscape workaround**: `FLAG_ACTIVITY_CLEAR_TASK` clears NetEase's activity stack, forcing a cold start with splash screen (~8s) on the next song — even if the next song doesn't use CLEAR_TASK itself
2. **Cross-platform switches**: Switching from one music app to another may require the target app to cold-start if it was previously force-stopped or not recently active

The current timeout logic only extends to 20s for "NetEase + landscape" but misses the case where the app is in a cold-start state regardless of current orientation.

## What Changes

- **Smart timeout based on app readiness**: Instead of a fixed 5s timeout with a special 20s case for landscape NetEase, detect whether the target app likely needs a cold start (splash screen) and use an appropriate timeout:
  - **Warm start** (~5s): App is already running with an active MediaSession/controller
  - **Cold start** (~15s): App has no active MediaSession, was recently force-stopped, or had its activity stack cleared via CLEAR_TASK

## Non-goals

- Changing the timeout for songs that fail availability checks (those are pre-filtered)
- Adding retry logic for timed-out songs

## Capabilities

### New Capabilities
- `smart-playback-timeout`: Dynamically adjust the playback timeout based on whether the target music app is in a cold-start or warm-start state

### Modified Capabilities
<!-- No existing spec-level behavior changes -->

## Impact

- **PlaybackService.kt**: Replace the hardcoded timeout logic with a smart check based on MediaMonitorService's controller state
- **MediaMonitorService.kt**: May need a method to check if a controller exists for a given package
- **Platform-specific**: Affects all three platforms (NetEase, QQ Music, Bilibili) — any of them can cold-start
