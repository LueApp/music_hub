## Why

When the phone is held in landscape orientation, launching a NetEase Cloud Music song via deep link opens the portrait player (letterboxed), instead of the full-screen landscape player (`PlayerLandscapeActivity`). QQ Music and Bilibili handle landscape naturally. The existing `toggleAutoRotate()` workaround in `DeepLinkLauncher` doesn't work because it toggles auto-rotate without recreating the activity, so NetEase's `OrientationEventListener` never fires (it needs a fresh registration to deliver the initial sensor event).

## What Changes

- **Replace the broken `toggleAutoRotate()` approach** with a proven rotation-toggle sequence:
  1. Before launching, detect if the device is in landscape orientation
  2. If landscape: temporarily force portrait system rotation (`ACCELEROMETER_ROTATION=0`, `USER_ROTATION=0`)
  3. Launch the deep link with `FLAG_ACTIVITY_CLEAR_TASK` to force a fresh `PlayerActivity` (which registers a new `OrientationEventListener`)
  4. **Event-driven trigger**: Use `MediaMonitorService` to detect when NetEase's playback notification appears (meaning the player is fully loaded)
  5. Restore auto-rotation → system detects landscape via accelerometer → NetEase's fresh listener fires → launches `PlayerLandscapeActivity`

## Non-goals

- Modifying NetEase Cloud Music's internal behavior
- Supporting other orientation issues on QQ Music or Bilibili (they work natively)
- Using the internal `orpheus://nm/play/land` deep link (it's not routable from external apps — `PlayerLandscapeActivity` is not exported)

## Capabilities

### New Capabilities
- `netease-landscape-workaround`: Orientation rotation workaround that forces NetEase Cloud Music into landscape mode when the device is held horizontally during song launch

### Modified Capabilities
<!-- No existing spec-level behavior changes required -->

## Impact

- **DeepLinkLauncher.kt**: Replace `toggleAutoRotate()` with new landscape-aware launch sequence; add `FLAG_ACTIVITY_CLEAR_TASK` for NetEase landscape launches
- **MediaMonitorService.kt**: Add callback/event mechanism to notify when NetEase playback starts (trigger for rotation restore)
- **PlaybackService.kt**: Coordinate the rotation restore timing with the playback detection flow
- **Android permissions**: Already has `WRITE_SETTINGS` permission (used by existing `toggleAutoRotate`)
- **Platform-specific**: Only affects NetEase (`com.netease.cloudmusic`); QQ Music and Bilibili are unaffected
