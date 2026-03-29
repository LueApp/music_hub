## Context

NetEase Cloud Music has a separate `PlayerLandscapeActivity` for landscape playback. It's launched internally when NetEase's `OrientationEventListener` detects a portrait→landscape rotation. The listener reads the hardware accelerometer directly (not system rotation settings), so it only fires on actual sensor data changes or fresh listener registration.

The current `toggleAutoRotate()` in `DeepLinkLauncher` briefly disables/re-enables auto-rotate after 1s. This fails because:
1. The deep link reuses the existing `PlayerActivity` (no fresh listener registration)
2. The accelerometer data hasn't changed (phone was already landscape), so the listener has nothing new to report

**Proven working sequence** (validated via adb testing):
1. Force portrait system rotation before launch
2. Launch deep link with `FLAG_ACTIVITY_CLEAR_TASK` (forces fresh activity + fresh listener)
3. Wait for player to fully initialize
4. Restore auto-rotation → accelerometer reports landscape → fresh listener fires → NetEase launches `PlayerLandscapeActivity`

## Goals / Non-Goals

**Goals:**
- NetEase enters full-screen landscape mode when the device is in landscape orientation
- Event-driven rotation restore (no fixed 8-second delay)
- Clean fallback: if detection fails or times out, restore auto-rotation anyway

**Non-Goals:**
- Modifying behavior for QQ Music or Bilibili
- Handling locked-screen landscape launch (locked screen already has separate path)
- Remote controller mode (controller doesn't launch local deep links)

## Decisions

### 1. Use `FLAG_ACTIVITY_CLEAR_TASK` instead of force-stop

**Decision**: Add `FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK` to the intent flags when launching NetEase in landscape mode.

**Why**: `CLEAR_TASK` clears the activity stack and creates a fresh `PlayerActivity`, which registers a new `OrientationEventListener`. Force-stop (`am force-stop`) would kill the entire process including any background playback, and can't be called from within our app without root.

**Alternative considered**: Launching without `CLEAR_TASK` — doesn't work because the existing activity's listener already has the current sensor reading and won't fire again.

### 2. Event-driven rotation restore via MediaMonitorService

**Decision**: After launching the deep link, listen for NetEase's `MediaController` to report `STATE_PLAYING`. This indicates the player is fully loaded and the `OrientationEventListener` is registered. Then restore auto-rotation.

**Why**: Fixed delays (8s) are bad UX. The `MediaMonitorService` already monitors active `MediaSession` instances and detects playback state changes. We can add a one-shot callback that fires when NetEase transitions to `STATE_PLAYING`.

**Alternative considered**: Polling activity state via `ActivityManager` — requires additional permissions and is less reliable than the existing notification listener infrastructure.

### 3. Force portrait before launch, not after

**Decision**: Disable auto-rotation and set `USER_ROTATION=0` (portrait) *before* launching the deep link. Restore only after playback is detected.

**Why**: This ensures the fresh `PlayerActivity` starts in portrait mode. When auto-rotation is later restored, the system detects the physical landscape orientation via accelerometer, causing a genuine portrait→landscape transition that triggers NetEase's listener.

**Alternative considered**: Launch first, then toggle rotation — doesn't create a genuine rotation transition because the activity was never in portrait.

### 4. Timeout with guaranteed restore

**Decision**: If playback isn't detected within 15 seconds, restore auto-rotation anyway. This prevents the user from being stuck in forced portrait mode if something goes wrong (NetEase crashes, network error, etc.).

**Why**: Safety net. The rotation settings are system-wide, so failing to restore them would affect all other apps.

### 5. Skip workaround when already in portrait

**Decision**: Only apply the rotation workaround when the device is currently in landscape orientation. In portrait, use the normal launch path (no `CLEAR_TASK`, no rotation toggle).

**Why**: The workaround is only needed for the landscape case. Adding `CLEAR_TASK` in portrait would unnecessarily restart the activity stack and potentially show the splash screen.

## Risks / Trade-offs

- **[Risk] Splash screen delay**: `CLEAR_TASK` may trigger NetEase's splash/ad screen, adding 3-5 seconds before the player loads.
  → **Mitigation**: This is unavoidable but only happens when the device is in landscape. The event-driven trigger minimizes the post-load wait.

- **[Risk] `WRITE_SETTINGS` permission not granted**: The app needs `android.permission.WRITE_SETTINGS` to modify `ACCELEROMETER_ROTATION` and `USER_ROTATION`.
  → **Mitigation**: Already required and checked by the existing `toggleAutoRotate()`. If not granted, skip the workaround entirely (same as current behavior).

- **[Risk] Auto-rotation not restored on crash**: If the app crashes between forcing portrait and restoring auto-rotation, the user is stuck in portrait lock.
  → **Mitigation**: 15-second timeout guarantee. Also consider restoring rotation in `onDestroy()` of PlaybackService.

- **[Risk] NetEase-to-NetEase same-platform transition**: The existing double-send logic (send deep link twice with 2s gap) may interfere with `CLEAR_TASK`.
  → **Mitigation**: The second send already uses `skipAutoRotate = true`, so only the first launch triggers the rotation workaround. The second send should NOT use `CLEAR_TASK` (it just needs to override NetEase's auto-advance).

- **[Trade-off] Only works in standalone/player mode**: In controller mode, deep links aren't launched locally.
  → **Acceptable**: Controller mode sends commands to the player phone, which handles its own deep link launching.
