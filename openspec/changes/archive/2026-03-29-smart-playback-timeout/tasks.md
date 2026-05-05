## 1. MediaMonitorService: Add controller presence check

- [x] 1.1 Add `hasActiveController(packageName: String): Boolean` method to `MediaMonitorService` that checks if `activeControllers` contains the given package. Thread-safe via `controllersLock`.

## 2. PlaybackService: Replace timeout logic with smart detection

- [x] 2.1 Replace the `isLandscapeForTimeout` + hardcoded 20s logic in `schedulePlaybackTimeout()` with a call to `MediaMonitorService.getInstance()?.hasActiveController(targetPackage)`. Use 5s for warm start (controller exists), 15s for cold start (no controller).
- [x] 2.2 Add constants: `PLAYBACK_TIMEOUT_WARM_MS = 5000L` and `PLAYBACK_TIMEOUT_COLD_MS = 15000L`. Remove the old landscape-specific timeout code.

## 3. Build and Test

- [x] 3.1 Build with `pixi run build` and verify no compilation errors.
- [x] 3.2 Deploy and test: play songs in landscape (NetEase), verify no skipping. Test cross-platform switches (NetEase → QQ Music). Test normal portrait playback.
