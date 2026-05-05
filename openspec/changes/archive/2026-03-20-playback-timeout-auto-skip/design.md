## Context

PlaybackService currently has a two-phase playback flow:
1. **Pre-launch check**: `checkSongAvailability()` queries platform APIs to detect taken-down songs (fnote==4001 for QQ Music, st<0 for NetEase). If unavailable, auto-skips before launching.
2. **Launch**: `doLaunchSong()` fires a deep link via `DeepLinkLauncher`, then relies on `MediaMonitorService` to detect song completion and trigger `playNext()`.

The gap: if a song passes the pre-launch check but fails at runtime (VIP-only, region-locked, temporary server error, "resource unavailable"), there is no detection. MediaMonitorService only detects natural song endings (position near duration) and user pauses. A failed launch produces no PLAYING state, so the queue hangs indefinitely.

MediaMonitorService already tracks playback state via `MediaController.Callback.onPlaybackStateChanged()` and polls position every 1 second. It knows when a controller enters PLAYING state. This existing infrastructure can be leveraged.

The `manualControlActive` flag (set during song switches, cleared after 3 seconds via `onNewSongStarted()`) already provides a "song was just launched" signal. After this window expires, we can check whether playback actually started.

## Goals / Non-Goals

**Goals:**
- Detect when a launched song fails to start playing within a reasonable timeout
- Auto-skip failed songs with user notification (toast)
- Log failures with enough detail to identify problematic songs
- Integrate with existing `consecutiveSkips` safety limit
- Work in standalone and player modes (controller mode delegates to player)

**Non-Goals:**
- Persisting failure history to database
- Retry logic or alternative playback strategies
- Detecting mid-playback failures (song starts then stops unexpectedly)
- Modifying the pre-launch availability check
- Handling Bilibili (excluded from MediaMonitorService auto-advance by design)

## Decisions

### 1. Timeout-based detection via Handler.postDelayed in PlaybackService

**Decision**: After `doLaunchSong()` completes, schedule a delayed check (15 seconds) that queries MediaMonitorService for whether playback is active. If no PLAYING state is detected, treat as failure and auto-skip.

**Alternatives considered**:
- Callback from MediaMonitorService when PLAYING detected: More reactive, but adds coupling between services and requires a new broadcast/listener pattern. The timeout approach is simpler and sufficient.
- Coroutine delay + check: Would work, but Handler.postDelayed is already the pattern used in doLaunchSong() for launch delays and in MediaMonitorService for repeated pauses. Consistency matters.

**Rationale**: 15 seconds accounts for: deep link launch delay (100-300ms) + app startup time + song loading. QQ Music typically starts playing within 3-5 seconds. 15 seconds provides generous margin while still being responsive enough that the user doesn't wait too long. The timeout is cancelled if `playNext()`/`playPrevious()`/`stop()` is called before it fires (to avoid stale timeouts triggering on the wrong song).

### 2. Query MediaMonitorService.getPlaybackInfo() for detection

**Decision**: Use the existing `getPlaybackInfo()` method to check if any media controller is in PLAYING state when the timeout fires.

**Rationale**: `getPlaybackInfo()` already iterates active controllers and returns `PlaybackInfo` with `isPlaying` flag. No new API needed. If it returns null or `isPlaying == false`, the song failed to start.

### 3. Track expected song to prevent stale timeout triggers

**Decision**: Store the song ID when scheduling the timeout. When the timeout fires, compare against the current song. If they differ (user manually skipped), cancel silently.

**Rationale**: Without this, a timeout scheduled for song A could fire after the user manually skipped to song B, incorrectly skipping B.

### 4. Reuse consecutiveSkips counter for timeout failures

**Decision**: Increment the same `consecutiveSkips` counter used by the pre-launch availability check. Both represent "songs that couldn't be played."

**Rationale**: The safety limit (MAX_CONSECUTIVE_SKIPS = 10) should apply to all skip reasons combined. A playlist where 5 songs are API-unavailable and 5 more timeout at runtime should still trigger the safety stop.

### 5. Distinct toast message for timeout vs pre-launch skip

**Decision**: Use "跳过: {title} (播放超时)" for timeout skips, distinct from "跳过: {title} ({reason})" for pre-launch skips.

**Rationale**: Helps the user distinguish between "song is known to be unavailable" and "song failed to play for unknown reasons."

## Risks / Trade-offs

- **[False positive on slow networks]** → 15-second timeout may be too aggressive on very slow connections where the music app takes longer to buffer. Mitigation: 15 seconds is generous for typical mobile networks; can be tuned later if needed.

- **[False positive when user interacts with music app]** → If the user manually pauses the song within the timeout window, the check would see no PLAYING state and incorrectly skip. Mitigation: The `manualControlActive` flag is cleared after 3 seconds, and the timeout fires at 15 seconds. If the user paused, the playback state would have been PLAYING at some point (MediaMonitorService tracks this). We can additionally check if `maxPositionReached > 0` to confirm playback did start.

- **[Stale timeout after rapid skipping]** → User rapidly pressing next could leave orphaned timeouts. Mitigation: Cancel pending timeout in `launchCurrentSong()` before scheduling a new one, and verify song ID match when timeout fires.

- **[Controller mode]** → In controller mode, PlaybackService runs on the controller phone but MediaMonitorService runs on the player phone. The timeout check via `MediaMonitorService.getInstance()` would return null on the controller. Mitigation: Skip timeout detection when `RemoteMode.isController()` is true — the player phone handles its own timeout detection.
