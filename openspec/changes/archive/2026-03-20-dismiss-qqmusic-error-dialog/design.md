## Context

When QQ Music receives a deep link for an unavailable song, it opens and displays a modal dialog ("该歌曲暂无版权" — song temporarily has no copyright). This dialog has a close button with resource ID `com.tencent.qqmusic:id/close_btn` (bounds roughly `[498,1618][582,1702]`). The dialog blocks user interaction with the rest of the QQ Music UI.

The recently added playback timeout in PlaybackService detects this failure (no PLAYING state within 15 seconds) and auto-skips to the next song. However, the error dialog remains on screen because nothing dismisses it. The next deep link launches behind the dialog.

`PlayerAccessibilityService` already monitors QQ Music's view hierarchy and can perform gestures and global actions. It has `canRetrieveWindowContent` and access to `performGlobalAction()`. Adding dialog dismissal to this service is a natural extension.

## Goals / Non-Goals

**Goals:**
- Dismiss QQ Music's error/no-copyright dialog when a playback timeout triggers auto-skip
- Use the existing `PlayerAccessibilityService` to find and click the close button
- Fall back to GLOBAL_ACTION_BACK if the close button isn't found
- Dismiss before launching the next song so the new deep link opens cleanly

**Non-Goals:**
- Detecting or classifying the specific error type shown in the dialog
- Handling dialogs from NetEase or Bilibili
- Proactive dialog detection (only dismiss when triggered by playback timeout)
- Working without the accessibility service enabled (graceful no-op if not running)

## Decisions

### 1. Two-strategy dismissal: close_btn click → BACK fallback

**Decision**: First try to find `com.tencent.qqmusic:id/close_btn` via `findAccessibilityNodeInfosByViewId()` and click it. If not found, use `performGlobalAction(GLOBAL_ACTION_BACK)` as fallback.

**Alternatives considered**:
- BACK-only approach: Simpler, but BACK may navigate away from QQ Music entirely if no dialog is present, which could interfere with subsequent deep link launches.
- Gesture tap at fixed coordinates of close button: Fragile — button position varies across devices and QQ Music versions.

**Rationale**: The close button resource ID (`close_btn`) is a stable identifier used by QQ Music's standard dialog template. Clicking it is precise and safe. BACK is the universal fallback that QQ Music responds to for dismissing dialogs.

### 2. Call dismissal from PlaybackService timeout runnable, before playNext()

**Decision**: In the timeout runnable (where we detect the song failed), call `PlayerAccessibilityService.getInstance()?.dismissErrorDialog()` before calling `playNext()`. Add a small delay (500ms) between dismiss and next song launch to let the dialog animation complete.

**Alternatives considered**:
- Dismiss in DeepLinkLauncher before each launch: Would dismiss on every song launch, even when no dialog is present. Unnecessary side effects.
- Dismiss from MediaMonitorService: Wrong layer — MediaMonitorService monitors playback state, not UI state.

**Rationale**: The timeout runnable is the exact point where we know a song failed and we're about to skip. This is the right place to clean up the failed state before moving on.

### 3. Graceful no-op when accessibility service is unavailable

**Decision**: If `PlayerAccessibilityService.getInstance()` returns null (service not enabled), skip dismissal silently. The next deep link will still launch — it just won't dismiss the old dialog.

**Rationale**: The accessibility service is optional. Users who don't enable it get the same behavior as before this change (dialog stays, next song launches behind it). No degradation.

### 4. Standalone and player modes only

**Decision**: Dialog dismissal runs on the device showing QQ Music. In controller mode, PlaybackService sends commands to the player phone via remote API — the player phone's PlaybackService handles the timeout and dismissal locally.

**Rationale**: The accessibility service runs on the phone where QQ Music is displayed. In controller mode, the controller phone doesn't have QQ Music open, so there's no dialog to dismiss. The player phone handles it.

## Risks / Trade-offs

- **[QQ Music UI changes]** → The `close_btn` resource ID could change in a QQ Music update. Mitigation: BACK fallback provides a reliable secondary path. The close button ID can be updated when observed to change.

- **[Dismiss timing]** → The 500ms delay between dismiss and next song launch may be too short if the animation is slow, or unnecessary if QQ Music processes the click instantly. Mitigation: 500ms is conservative; can be tuned based on testing.

- **[False dismiss]** → If a non-error dialog happens to have a `close_btn` ID, it could be incorrectly dismissed. Mitigation: Dismissal is only triggered by the playback timeout path, not proactively. The only time we dismiss is when we already know the song failed.
