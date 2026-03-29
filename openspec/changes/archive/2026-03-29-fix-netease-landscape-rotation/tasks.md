## 1. MediaMonitorService: Add playback-started callback

- [x] 1.1 Add a one-shot callback mechanism to `MediaMonitorService` that fires when a specific platform's `MediaController` transitions to `STATE_PLAYING`. This will be used by `DeepLinkLauncher` to detect when NetEase has finished loading and started playback. Include a timeout parameter so the callback auto-fires after a maximum wait (15s).

## 2. DeepLinkLauncher: Replace toggleAutoRotate with landscape-aware launch

- [x] 2.1 Add a `isDeviceLandscape(context)` helper method to detect current device orientation (check `Resources.getConfiguration().orientation`).
- [x] 2.2 Add a `forcePortraitRotation(context)` method that sets `ACCELEROMETER_ROTATION=0` and `USER_ROTATION=0` via `Settings.System`. Add a corresponding `restoreAutoRotation(context)` method that sets `ACCELEROMETER_ROTATION=1`.
- [x] 2.3 Modify the NetEase launch path in `launch()`: when device is landscape, call `forcePortraitRotation()` before launching, add `FLAG_ACTIVITY_CLEAR_TASK` to the intent flags, then register the MediaMonitorService playback-started callback to call `restoreAutoRotation()` when triggered.
- [x] 2.4 Remove the old `toggleAutoRotate()` method and its constants (`AUTO_ROTATE_TOGGLE_DELAY_MS`, `AUTO_ROTATE_OFF_DURATION_MS`). Remove the call site at line 104-106.

## 3. PlaybackService: Ensure double-send compatibility

- [x] 3.1 Verify that the NetEase double-send logic in `doLaunchSong()` passes `skipAutoRotate=true` on the second send (already the case). Ensure the second send does NOT add `FLAG_ACTIVITY_CLEAR_TASK`. Add a new parameter or flag to `DeepLinkLauncher.launch()` to control whether `CLEAR_TASK` is used (e.g., `skipClearTask: Boolean = false`).

## 4. Build and Test

- [x] 4.1 Build the app with `pixi run build` and verify no compilation errors.
- [x] 4.2 Deploy to device with `pixi run deploy`. Test: hold phone in landscape → play a NetEase song → verify full-screen landscape player appears. Test: play in portrait → verify normal portrait player. Test: play QQ Music/Bilibili in landscape → verify no rotation workaround applied.
