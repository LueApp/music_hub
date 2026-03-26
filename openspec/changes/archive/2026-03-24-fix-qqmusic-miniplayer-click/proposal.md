## Why

The QQ Music mini player / music card click functionality has stopped working. The `PlayerAccessibilityService` uses hardcoded, obfuscated resource IDs (`cxy` for the music card, `jrh`/`jro` for the mini player) to locate and click UI elements in QQ Music. These IDs change with every QQ Music app update (they already changed once: `cxs` → `cxy`, `jqt`/`jqv` → `jrh`/`jro`). A recent QQ Music update has invalidated the current IDs, causing all ID-based strategies to fail and fall through to the hardcoded coordinate fallback (which is device-specific and unreliable).

## What Changes

- **Update obfuscated view IDs**: Discover the current QQ Music resource IDs for the music card and mini player bar, and update `PlayerAccessibilityService` accordingly.
- **Add a debug/discovery mode**: Add a UI tree dump capability to `PlayerAccessibilityService` that logs all visible nodes with their IDs, classes, bounds, and content descriptions when the click strategies fail. This will make future ID changes easy to diagnose without guessing.
- **Improve resilience**: Add a heuristic fallback strategy that searches for clickable elements by structural properties (class name, bounds position, content description) rather than only by resource ID, so the service degrades gracefully when IDs change.

## Non-goals

- Replacing the accessibility service approach entirely (no better alternative exists on Android for this use case).
- Supporting additional platforms beyond QQ Music in the accessibility service.
- Changing the deep link format or launch flow — only the post-launch click logic is affected.

## Capabilities

### New Capabilities
- `qqmusic-accessibility-resilience`: Heuristic-based mini player detection and UI tree dump for diagnosing QQ Music layout changes.

### Modified Capabilities
- `qqmusic-dialog-dismissal`: The close button resource ID (`close_btn`) may also have changed and needs verification.

## Impact

- **Code**: `PlayerAccessibilityService.kt` — primary changes (ID updates, heuristic fallback, debug dump).
- **Platform affected**: QQ Music only.
- **No new permissions required** — the existing `canPerformGestures` and `canRetrieveWindowContent` accessibility flags are sufficient.
- **No API or database changes**.
