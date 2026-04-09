## Why

When browsing the playlist queue in the floating window, there's no way to jump directly to a specific song — the user must tap "next" repeatedly. There's also no way to reorder the queue from the floating window. Adding double-tap-to-play and drag-to-reorder on queue items gives users full control over playback directly from the floating window.

## What Changes

- Add double-tap gesture detection on queue song items in the floating window
- On double-tap, jump to and play the tapped song via `PlaybackService.playAtIndex()`
- Add drag-to-reorder on queue items using `ItemTouchHelper` for long-press drag
- Add `moveInQueue(from, to)` API to `PlaybackService` to support reordering
- Support both local and remote (controller) modes for both features
- Provide visual feedback on first tap (double-tap hint) and during drag (elevation)

## Non-goals

- Changing single-tap behavior (reserved for future use, e.g. song details)
- Modifying the main app's playlist UI behavior
- Persisting queue order changes to the database (reorder is queue-only, transient)

## Capabilities

### New Capabilities
- `queue-double-tap-play`: Double-tap gesture on floating window queue items to jump to and play the selected song
- `queue-drag-reorder`: Long-press drag to reorder songs in the floating window queue

### Modified Capabilities

## Impact

- **FloatingWindowService.kt**: Wire up double-tap handler and attach `ItemTouchHelper` for drag-to-reorder
- **QueueAdapter.kt**: May need to support drag handle or visual feedback during drag
- **PlaybackService.kt**: Add `moveInQueue(from, to)` method for reordering the queue
- **RemoteClient.kt / RemoteServer.kt**: May need a `moveInQueue` remote command for controller mode
- No new permissions required
- All platforms (NetEase, QQ Music, Bilibili) are affected equally since this operates at the queue level
