## Context

The Music Hub app supports a LAN-based remote control mode where one phone (PLAYER) runs an embedded NanoHTTPD server (`RemoteServer`) and another phone (CONTROLLER) sends commands via HTTP/WebSocket. The `RemoteServer` broadcasts the current playback state every 500ms via WebSocket to connected controllers.

The crash occurs on the **player phone** when the first song finishes. The sequence is:

1. `MediaMonitorService` detects song end → sends `ACTION_SONG_FINISHED` broadcast
2. `PlaybackService.playNext()` is called on the **main thread** → calls `launchCurrentSong()` → starts a coroutine for availability check → calls `doLaunchSong()`
3. During this transition, `doLaunchSong()` calls `MediaMonitorService.pauseAllMedia()` which modifies `activeControllers` iteration state, and `onNewSongStarted()` which modifies `songEndTriggered` / `manualControlActive` flags
4. **Concurrently**, `RemoteServer.startBroadcasting()` is running on `Dispatchers.IO` and calls `buildCurrentState()` every 500ms
5. `buildCurrentState()` calls `MediaMonitorService.getInstance()?.getPlaybackInfo()` which iterates over `activeControllers` — a `mutableMapOf` with no synchronization
6. The main thread is modifying `activeControllers` (via `onActiveSessionsChanged()` or `pauseAllMedia()`) at the same time → **`ConcurrentModificationException`** crashes the broadcast coroutine, and potentially the entire server

The `activeControllers` map is accessed from:
- **Main thread**: `onActiveSessionsChanged()`, `pauseAllMedia()`, `pauseSpecificPackage()`, `togglePlayPause()`, `seekTo()`, `handlePlaybackStateChange()`, `pollCurrentPosition()`
- **IO thread (via RemoteServer)**: `getPlaybackInfo()` (called from `buildCurrentState()`)
- **NanoHTTPD server threads**: `handleTogglePlayPause()`, `handleSeek()` dispatch to main thread via `mainHandler.post{}`, so these are safe

## Goals / Non-Goals

**Goals:**
- Eliminate the `ConcurrentModificationException` crash in `MediaMonitorService` when accessed from the RemoteServer broadcast thread
- Make `RemoteServer.buildCurrentState()` resilient to transient exceptions so the broadcast loop survives individual errors
- Ensure playback continues seamlessly through song transitions in PLAYER mode

**Non-Goals:**
- Redesigning the thread model of `MediaMonitorService` (keep changes minimal)
- Fixing controller-side data loading issues (separate change)
- Adding new thread-safe collection types or concurrency frameworks
- Changing the WebSocket broadcast interval or protocol

## Decisions

### Decision 1: Synchronize `activeControllers` access with a dedicated lock

**Choice**: Add a `private val controllersLock = Any()` to `MediaMonitorService` and wrap all reads/writes to `activeControllers` (and `controllerCallbacks`) in `synchronized(controllersLock)` blocks. Inside `getPlaybackInfo()`, `togglePlayPause()`, and `seekTo()`, take a snapshot of the controllers (`activeControllers.values.toList()`) under the lock, then iterate the snapshot outside the lock.

**Rationale**: This is the simplest approach that matches the existing codebase style (e.g., `PlaybackService` already uses `synchronized(queueLock)` for its queue). It avoids introducing new dependencies like `ConcurrentHashMap` which would change the iteration semantics and still not prevent `ConcurrentModificationException` during iteration.

**Alternatives considered**:
- `ConcurrentHashMap`: Doesn't prevent `ConcurrentModificationException` when iterating while another thread modifies the map. Still needs snapshot-based iteration.
- Posting all `getPlaybackInfo()` calls to the main thread: Would add latency to the 500ms broadcast loop and could cause deadlocks with `runBlocking`.
- Using `@Synchronized` on every method: Too coarse-grained, would serialize all media monitoring.

### Decision 2: Wrap `buildCurrentState()` in try-catch within the broadcast loop

**Choice**: In `RemoteServer.startBroadcasting()`, wrap the `buildCurrentState()` + `send()` block in a try-catch that logs the error and continues the loop. This is a defense-in-depth measure — even after fixing the root cause, the broadcast loop should not crash from any transient error.

**Rationale**: The broadcast loop is critical for the remote control experience. A single exception should not kill the entire WebSocket state broadcast. NanoHTTPD server threads should be similarly resilient, but they already have a top-level try-catch in `serve()`.

### Decision 3: Snapshot-based iteration pattern

**Choice**: For `getPlaybackInfo()`, `togglePlayPause()`, and `seekTo()`, take a snapshot of `activeControllers.values.toList()` under the lock, then iterate the snapshot without holding the lock.

**Rationale**: These methods call `controller.transportControls.*` which may be slow (IPC to the media app). Holding the lock during these calls would block `onActiveSessionsChanged()` on the main thread, potentially causing UI jank or ANR. The snapshot pattern (copy under lock, iterate outside lock) is standard for this case.

## Risks / Trade-offs

- **[Minor race] Controller snapshot may be stale**: Between taking the snapshot and iterating, a controller might be removed. Calling methods on a removed `MediaController` may throw — but these calls are already wrapped in try-catch blocks, so this is safe.
- **[Performance] Snapshot allocation**: `toList()` allocates a new list on every call. With typically 1-3 controllers, this is negligible.
- **[Lock contention] Broadcast loop vs main thread**: The lock is held briefly for the snapshot copy. The 500ms broadcast interval means contention is minimal.
