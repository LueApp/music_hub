## Why

When switching from NetEase Cloud Music to QQ Music (cross-platform song transition), the user hears a brief burst of a third song ("So Called Love Song") from NetEase's internal queue. NetEase auto-advances to its next song ~2-3 seconds after receiving pause/stop commands, and the current re-pause mechanism doesn't catch it fast enough because it only checks for `STATE_PLAYING` — missing the intermediate states (PAUSED→STOPPED→PLAYING) that NetEase cycles through during auto-advance.

## What Changes

- **Improve re-pause detection**: Extend `pauseSpecificPackage` to also catch `STATE_BUFFERING` and newly-started playback (position=0), not just `STATE_PLAYING`. NetEase transitions through state=2 (PAUSED) → state=6 (STOPPED) → state=3 (PLAYING) during auto-advance, and the current code misses the window.
- **Add reactive re-pause on state change**: Instead of relying solely on scheduled timer-based re-pauses, also trigger re-pause reactively when we observe a state change from the old platform's controller during the cross-platform switch window. This catches auto-advance the instant it happens rather than waiting for the next scheduled check.
- **Remove `stop()` from re-pause**: The `stop()` transport command in `pauseSpecificPackage` may be too aggressive and could interfere with other media sessions. Only use `pause()` for re-pauses (keep `stop()` only in the initial `pauseAllMedia` call).

## Non-goals

- Changing the early song-end detection threshold or timing
- Modifying how deep links are launched
- Addressing the QQ Music or Bilibili auto-advance behavior (only NetEase exhibits this issue currently)
- Background song switching (known Android limitation)

## Capabilities

### New Capabilities

_None — this is a bug fix within the existing cross-platform pause mechanism._

### Modified Capabilities

_None — no spec-level behavior changes, only implementation fixes to the existing cross-platform pause logic._

## Impact

- **Affected code**: `MediaMonitorService.kt` — `pauseSpecificPackage()`, `scheduleRepeatedPause()`, and the `onPlaybackStateChanged` callback logic
- **Affected platforms**: Primarily NetEase Cloud Music (`com.netease.cloudmusic`), which is the platform that auto-advances after receiving pause commands
- **Risk**: Low — changes are scoped to the re-pause mechanism within an existing cross-platform switch flow. No new permissions or dependencies needed.
