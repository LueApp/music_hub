## Why

When a QQ Music song is unavailable (no copyright, VIP-only, region-locked), QQ Music shows a blocking popup dialog ("该歌曲暂无版权") that covers the screen. Even though PlaybackService now auto-skips via the playback timeout, the dialog remains visible on screen. The next song's deep link launches behind this dialog, and the user must manually dismiss it. This defeats the purpose of auto-skip — the queue advances but the screen is stuck on an error dialog.

## What Changes

- Add a `dismissErrorDialog()` method to `PlayerAccessibilityService` that finds and clicks QQ Music's close button (`com.tencent.qqmusic:id/close_btn`) or falls back to `performGlobalAction(GLOBAL_ACTION_BACK)`
- Call this dismiss method from `PlaybackService` before launching the next song when a playback timeout triggers, to clear the error dialog before the new deep link fires

## Non-goals

- No detection of specific error types (copyright vs VIP vs other) — just dismiss any blocking dialog
- No changes to the pre-launch availability check
- No changes to the playback timeout mechanism itself
- No handling of dialogs from NetEase or Bilibili (not observed as an issue)

## Capabilities

### New Capabilities
- `qqmusic-dialog-dismissal`: Automatic dismissal of QQ Music error/blocking dialogs when playback timeout triggers auto-skip

### Modified Capabilities
<!-- No existing specs to modify -->

## Impact

- **PlayerAccessibilityService**: New `dismissErrorDialog()` method that finds `close_btn` or sends BACK action
- **PlaybackService**: Modified timeout runnable to call dialog dismissal before `playNext()`
- **Affected platforms**: QQ Music only
- **No new permissions required** (uses existing AccessibilityService capabilities: `canRetrieveWindowContent`, `performGlobalAction`)
- **No database schema changes**
