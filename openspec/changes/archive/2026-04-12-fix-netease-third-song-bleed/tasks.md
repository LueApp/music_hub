## 1. Unify intermediate pause logic

- [x] 1.1 In `PlaybackService.doLaunchSong()`, refactor the `isSamePlatformNetEase` branch so that intermediate pause commands at 500ms and 1200ms are sent for BOTH portrait and landscape modes (currently only landscape sends pauses)
- [x] 1.2 Move the portrait-only deep link re-send at 2s into a separate conditional that checks `!isLandscapeForDoubleSend`, keeping it independent from the pause logic

## 2. Add diagnostic logging

- [x] 2.1 Add log entries for each intermediate pause with timing context (e.g., "Pausing NetEase auto-advance at 500ms (portrait)") in `PlaybackService.kt`

## 3. Build and test

- [x] 3.1 Run `pixi run build` to verify compilation
- [x] 3.2 Deploy to device and test NetEase-to-NetEase transition in portrait mode — verify no third-song audio bleed
