## 1. Improve orientation toggle in DeepLinkLauncher

- [x] 1.1 Add `Surface` and `WindowManager` imports to `DeepLinkLauncher.kt` for reading current device rotation
- [x] 1.2 Replace `toggleAutoRotate()` with new `forceOrientationForNetEase()` method that: reads current display rotation via `WindowManager`, checks if device is in landscape (rotation 90 or 270), and skips if in portrait
- [x] 1.3 In `forceOrientationForNetEase()`, save original `USER_ROTATION` value, set `USER_ROTATION` to match current device rotation, disable `ACCELEROMETER_ROTATION`, wait 500ms stabilization, then restore both settings to original values
- [x] 1.4 Update the NetEase-specific block in `launchNormal()` to call `forceOrientationForNetEase()` instead of `toggleAutoRotate()`
- [x] 1.5 Update timing constants: change `AUTO_ROTATE_TOGGLE_DELAY_MS` to 800ms (wait for NetEase activity to start) and `AUTO_ROTATE_OFF_DURATION_MS` to 500ms (longer stabilization window)

## 2. Build verification and testing

- [x] 2.1 Run `pixi run build` to verify the project compiles without errors
- [x] 2.2 Deploy to device with `pixi run deploy` and test: hold phone in landscape, launch a NetEase song from a playlist, verify NetEase enters landscape mode automatically
