## Why

When the Music Hub app is running in **PLAYER** mode (remote control server), the app crashes on the player phone after the first song finishes playing and the app attempts to advance to the next song. This makes the remote control feature unusable for continuous playback — the core use case.

## What Changes

- **Fix thread-safety in `RemoteServer.buildCurrentState()`**: The WebSocket broadcast loop runs on `Dispatchers.IO` and calls `MediaMonitorService.getPlaybackInfo()`, which iterates over `activeControllers` (a non-thread-safe `mutableMapOf`). The main thread modifies this map concurrently during song transitions, causing a `ConcurrentModificationException` crash.
- **Fix thread-safety in `MediaMonitorService.activeControllers` access**: Multiple methods (`getPlaybackInfo()`, `togglePlayPause()`, `seekTo()`, `pauseAllMedia()`) iterate over `activeControllers` without synchronization, while `onActiveSessionsChanged()` modifies it from the main thread. The broadcast loop on IO thread triggers the crash.
- **Guard `RemoteServer` broadcast against null service instances**: During song transitions, `PlaybackService.getInstance()` and `MediaMonitorService.getInstance()` can briefly return null. While the current code uses `?.` safe calls, the underlying `getPlaybackInfo()` crash occurs before the null check helps.

### Non-goals

- Changing the remote control protocol or API surface
- Fixing controller-side issues (those are tracked in `fix-remote-controller-data-loading`)
- Modifying the song-end detection logic in `MediaMonitorService`
- Adding new features to the remote control system

## Capabilities

### New Capabilities

- `thread-safe-media-monitor`: Thread-safe access to `MediaMonitorService` playback state from non-main threads (specifically the RemoteServer broadcast loop)

### Modified Capabilities

_None — no existing spec requirements are changing._

## Impact

- **Affected code**:
  - `MediaMonitorService.kt` — add synchronization to `activeControllers` access in `getPlaybackInfo()`, `togglePlayPause()`, `seekTo()`
  - `RemoteServer.kt` — wrap `buildCurrentState()` in try-catch to prevent broadcast loop crash from killing the server
- **Affected platforms**: All three (NetEase, QQ Music, Bilibili) — the crash occurs regardless of which platform's song finishes
- **No permission changes**: No new Android permissions required
- **No API changes**: The remote control REST/WebSocket API remains unchanged
- **No dependency changes**: No new libraries needed
