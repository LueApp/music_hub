## 1. Add reactive re-pause in handlePlaybackStateChange

- [x] 1.1 In `MediaMonitorService.kt` `handlePlaybackStateChange()`, add a check before the early return at line 261: if `isCrossPlatformSwitch` is true and `fromPackage == switchingFromPackage` and the state is PLAYING or BUFFERING, immediately call `pauseSpecificPackage(fromPackage)` and then return (skip normal processing)

## 2. Remove stop() from pauseSpecificPackage

- [x] 2.1 In `MediaMonitorService.kt` `pauseSpecificPackage()`, remove the `controller.transportControls.stop()` call (line 581), keeping only `controller.transportControls.pause()`

## 3. Build and verify

- [x] 3.1 Run `pixi run build` to verify the changes compile without errors
