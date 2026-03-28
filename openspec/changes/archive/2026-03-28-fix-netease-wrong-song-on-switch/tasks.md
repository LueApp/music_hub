## 1. Fix same-platform pause logic in MediaMonitorService

- [x] 1.1 In `pauseAllMedia()` in `MediaMonitorService.kt`, enable repeated pause scheduling for same-platform switches when the target platform is NetEase (`com.netease.cloudmusic`). Currently the code skips repeated pause for same-platform switches entirely — change the condition so that NetEase same-platform switches also get repeated pause attempts (e.g., 3-4 attempts over ~2s at 500ms intervals).
- [x] 1.2 Ensure `switchingFromPackage` is set to the NetEase package for same-platform NetEase switches so that `pauseSpecificPackage()` targets the correct controller.
- [x] 1.3 Limit the repeated pause window to ~2s (shorter than the cross-platform 4s window) to avoid pausing the newly launched song that arrives via deep link.

## 2. Build and test

- [x] 2.1 Run `pixi run build` to verify the change compiles without errors.
- [x] 2.2 Deploy to device with `pixi run deploy` and test: play a NetEase playlist, let early song-end detection trigger, verify the correct next song from Music Hub's queue plays (not NetEase's internal next song). Check logs with `adb logcat -s MediaMonitorService PlaybackService` to confirm repeated pause fires and correct song metadata appears.
