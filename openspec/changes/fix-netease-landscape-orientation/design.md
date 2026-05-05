## Context

When Music Hub launches a NetEase Cloud Music deep link via `orpheus://song/{id}`, the system calls `startActivity()` with `FLAG_ACTIVITY_NEW_TASK`. If the phone is already in landscape orientation, NetEase's activity starts but fails to detect the current orientation — it renders in portrait mode until the user manually rotates away and back.

The current workaround in `DeepLinkLauncher.toggleAutoRotate()` toggles `ACCELEROMETER_ROTATION` off (0) and back on (1) with fixed delays (1000ms wait, then 200ms off duration). This is unreliable because:
1. The timing doesn't account for how long NetEase's activity actually takes to become ready
2. Simply toggling auto-rotate off/on doesn't explicitly tell the system *which* rotation to apply — it just re-enables sensor-based detection, which may not trigger a configuration change if the system thinks nothing changed

The app already has `WRITE_SETTINGS` permission for this purpose.

## Goals / Non-Goals

**Goals:**
- Make NetEase Cloud Music reliably enter landscape mode when launched via deep link while the device is physically in landscape orientation
- Keep the fix self-contained in `DeepLinkLauncher` with no changes to other services or UI components

**Non-Goals:**
- Fixing orientation for QQ Music or Bilibili (no reported issues)
- Handling locked-screen launches (auto-rotate toggle is skipped when screen is locked)
- Supporting Android 16+ large screen orientation policy changes (min SDK is 26, target is 34)

## Decisions

### Decision 1: Use `USER_ROTATION` + `ACCELEROMETER_ROTATION` combo instead of just toggling auto-rotate

**Chosen approach**: Before launching the NetEase deep link, set `Settings.System.USER_ROTATION` to `Surface.ROTATION_270` (landscape) and temporarily disable `ACCELEROMETER_ROTATION`. After the deep link activity starts, re-enable auto-rotate. This forces the system to apply landscape rotation explicitly during the window where auto-rotate is off, and NetEase's activity receives the correct orientation configuration on start.

**Why this over the current approach**: The current toggle-only approach relies on the sensor re-detecting orientation after auto-rotate is re-enabled. But if the device is already stationary in landscape, the sensor may not trigger a new reading. Setting `USER_ROTATION` explicitly tells the system "the user wants landscape right now" — this is what happens when the user taps the rotation suggestion button in the nav bar.

**Why not `setRequestedOrientation()`**: We can only call `setRequestedOrientation()` on our own activities. We cannot control NetEase's activity orientation from our app.

### Decision 2: Detect actual device orientation before setting USER_ROTATION

**Chosen approach**: Use the device's `WindowManager` display rotation (or `OrientationEventListener`-based cached value) to detect whether the device is actually in landscape *before* applying the fix. If the device is in portrait, skip the toggle entirely — no point forcing landscape when the user is holding the phone upright.

**Why**: The current code always toggles auto-rotate for NetEase launches regardless of actual orientation. This is wasteful and could cause a brief flicker if the phone is in portrait. Detecting orientation first makes the fix targeted.

### Decision 3: Use a longer stabilization delay with verify-and-retry

**Chosen approach**: Wait 800ms after `startActivity()` (giving NetEase time to create its activity), then apply the `USER_ROTATION` + auto-rotate toggle sequence. Keep auto-rotate disabled for 500ms (up from 200ms) to give the system more time to deliver the configuration change to NetEase's activity.

**Why**: The previous 200ms off-duration was too short. NetEase's activity needs time to receive and process the configuration change. 500ms provides a more reliable window while still being imperceptible to the user.

### Decision 4: Restore USER_ROTATION to original value

**Chosen approach**: Save the original `USER_ROTATION` value before modifying it, and restore it when re-enabling auto-rotate. This ensures we don't leave the system in a state where manual rotation (when auto-rotate is off) would default to landscape.

**Why**: Modifying `USER_ROTATION` without restoring it affects behavior when the user manually disables auto-rotate later — the phone would lock to landscape instead of their previous preference (typically portrait/natural).

## Risks / Trade-offs

- **[Risk] Toggle fails if WRITE_SETTINGS is revoked** → Already handled: the current code checks `Settings.System.canWrite()` and skips if not granted. No change needed.

- **[Risk] USER_ROTATION values may differ across OEMs** → `Surface.ROTATION_0/90/180/270` are standard Android constants. The risk is low. Mitigation: use `display.rotation` to read the current physical rotation and set `USER_ROTATION` to match, rather than hardcoding a landscape value.

- **[Risk] Brief orientation flicker when auto-rotate is re-enabled** → Mitigation: The 500ms window where auto-rotate is off is short enough that most users won't notice, and by the time auto-rotate is re-enabled, NetEase has already applied the correct orientation.

- **[Trade-off] This is a system-level settings modification** → We're modifying global system settings (`ACCELEROMETER_ROTATION` and `USER_ROTATION`) temporarily. If the app crashes mid-toggle, auto-rotate could be left disabled. The existing code already has this risk with `ACCELEROMETER_ROTATION`; adding `USER_ROTATION` restoration to the finally block mitigates this.
